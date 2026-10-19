#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="${ROOT_DIR}/terraform"
SSH_KEY="${SSH_KEY:-${ROOT_DIR}/cs6650-a4-key.pem}"
SSH_USER="${SSH_USER:-ec2-user}"
APP_DIR="${APP_DIR:-/opt/cs6650-a4}"
MODE="${MODE:-leader-follower}"
WRITE_QUORUM_SIZE="${WRITE_QUORUM_SIZE:-5}"
READ_QUORUM_SIZE="${READ_QUORUM_SIZE:-1}"
THREADS="${THREADS:-32}"
DURATION_SECONDS="${DURATION_SECONDS:-600}"
KEY_SPACE="${KEY_SPACE:-100}"

if [[ "${MODE}" != "leader-follower" && "${MODE}" != "leaderless" ]]; then
  echo "MODE must be leader-follower or leaderless" >&2
  exit 1
fi

for command in terraform jq scp ssh mvn; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Missing required command: ${command}" >&2
    exit 1
  fi
done

if [[ ! -f "${SSH_KEY}" ]]; then
  echo "SSH key not found: ${SSH_KEY}" >&2
  exit 1
fi

chmod 400 "${SSH_KEY}"

PUBLIC_IPS=()
while IFS= read -r ip; do
  PUBLIC_IPS+=("${ip}")
done < <(terraform -chdir="${TF_DIR}" output -json kv_node_public_ips | jq -r '.[]')

PRIVATE_IPS=()
while IFS= read -r ip; do
  PRIVATE_IPS+=("${ip}")
done < <(terraform -chdir="${TF_DIR}" output -json kv_node_private_ips | jq -r '.[]')
LOAD_TESTER_PUBLIC_IP="$(terraform -chdir="${TF_DIR}" output -raw load_tester_public_ip)"
LEADERLESS_ALB_URL="$(terraform -chdir="${TF_DIR}" output -raw leaderless_alb_url)"

if [[ "${#PUBLIC_IPS[@]}" -ne 5 || "${#PRIVATE_IPS[@]}" -ne 5 ]]; then
  echo "Expected five KV nodes from Terraform output" >&2
  exit 1
fi

KV_JAR="${ROOT_DIR}/kv-node/target/kv-node-1.0-SNAPSHOT.jar"
LOAD_TESTER_JAR="${ROOT_DIR}/load-tester/target/load-tester-1.0-SNAPSHOT.jar"

echo "Building jars..."
mvn -q -DskipTests package

ssh_opts=(
  -i "${SSH_KEY}"
  -o StrictHostKeyChecking=accept-new
  -o ConnectTimeout=15
)

install_java() {
  local host="$1"
  ssh "${ssh_opts[@]}" "${SSH_USER}@${host}" \
    'if ! command -v java >/dev/null 2>&1; then sudo dnf install -y java-17-amazon-corretto-headless; fi'
}

copy_file() {
  local source="$1"
  local host="$2"
  local destination="$3"
  scp "${ssh_opts[@]}" "${source}" "${SSH_USER}@${host}:${destination}"
}

join_by_comma() {
  local IFS=,
  echo "$*"
}

private_url() {
  echo "http://$1:8080"
}

deploy_kv_node() {
  local index="$1"
  local public_ip="${PUBLIC_IPS[${index}]}"
  local role="follower"
  local follower_urls=""
  local peer_urls=""

  if [[ "${MODE}" == "leader-follower" ]]; then
    if [[ "${index}" -eq 0 ]]; then
      role="leader"
      local followers=()
      for follower_index in 1 2 3 4; do
        followers+=("$(private_url "${PRIVATE_IPS[${follower_index}]}")")
      done
      follower_urls="$(join_by_comma "${followers[@]}")"
    fi
  else
    local peers=()
    for peer_index in 0 1 2 3 4; do
      if [[ "${peer_index}" -ne "${index}" ]]; then
        peers+=("$(private_url "${PRIVATE_IPS[${peer_index}]}")")
      fi
    done
    peer_urls="$(join_by_comma "${peers[@]}")"
  fi

  echo "Deploying KV node $((index + 1)) to ${public_ip} (${MODE})..."
  install_java "${public_ip}"
  ssh "${ssh_opts[@]}" "${SSH_USER}@${public_ip}" "sudo mkdir -p '${APP_DIR}' && sudo chown '${SSH_USER}:${SSH_USER}' '${APP_DIR}'"
  copy_file "${KV_JAR}" "${public_ip}" "${APP_DIR}/kv-node.jar"

  ssh "${ssh_opts[@]}" "${SSH_USER}@${public_ip}" "sudo tee /etc/systemd/system/cs6650-kv-node.service >/dev/null" <<EOF
[Unit]
Description=CS6650 Assignment 4 KV Node
After=network-online.target
Wants=network-online.target

[Service]
User=${SSH_USER}
WorkingDirectory=${APP_DIR}
Environment=PORT=8080
Environment=ROLE=${role}
Environment=FOLLOWER_URLS=${follower_urls}
Environment=PEER_URLS=${peer_urls}
Environment=WRITE_QUORUM_SIZE=${WRITE_QUORUM_SIZE}
Environment=READ_QUORUM_SIZE=${READ_QUORUM_SIZE}
ExecStart=/usr/bin/java -jar ${APP_DIR}/kv-node.jar
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

  ssh "${ssh_opts[@]}" "${SSH_USER}@${public_ip}" \
    'sudo systemctl daemon-reload && sudo systemctl enable --now cs6650-kv-node && sudo systemctl restart cs6650-kv-node'
}

deploy_load_tester() {
  local public_ip="${LOAD_TESTER_PUBLIC_IP}"
  local leader_write_target
  local leader_read_targets
  leader_write_target="$(private_url "${PRIVATE_IPS[0]}")"

  local private_targets=()
  for private_ip in "${PRIVATE_IPS[@]}"; do
    private_targets+=("$(private_url "${private_ip}")")
  done
  leader_read_targets="$(join_by_comma "${private_targets[@]}")"

  echo "Deploying load tester to ${public_ip}..."
  install_java "${public_ip}"
  ssh "${ssh_opts[@]}" "${SSH_USER}@${public_ip}" "sudo mkdir -p '${APP_DIR}/results' && sudo chown -R '${SSH_USER}:${SSH_USER}' '${APP_DIR}'"
  copy_file "${LOAD_TESTER_JAR}" "${public_ip}" "${APP_DIR}/load-tester.jar"

  ssh "${ssh_opts[@]}" "${SSH_USER}@${public_ip}" "cat > '${APP_DIR}/run-load-test.sh' && chmod +x '${APP_DIR}/run-load-test.sh'" <<EOF
#!/usr/bin/env bash
set -euo pipefail

MODE="\${MODE:-${MODE}}"
WRITE_RATIO="\${WRITE_RATIO:-0.10}"
THREADS="\${THREADS:-${THREADS}}"
DURATION_SECONDS="\${DURATION_SECONDS:-${DURATION_SECONDS}}"
KEY_SPACE="\${KEY_SPACE:-${KEY_SPACE}}"
RESULT_DIR="\${RESULT_DIR:-${APP_DIR}/results}"
QUIET="\${QUIET:-true}"
QUIET_FLAG=""

if [[ "\${QUIET}" == "true" ]]; then
  QUIET_FLAG="--quiet"
fi

case "\${MODE}" in
  leaderless)
    WRITE_TARGET="${LEADERLESS_ALB_URL}"
    READ_TARGETS="${LEADERLESS_ALB_URL}"
    ;;
  leader-follower)
    WRITE_TARGET="${leader_write_target}"
    READ_TARGETS="${leader_read_targets}"
    ;;
  *)
    echo "MODE must be leader-follower or leaderless" >&2
    exit 1
    ;;
esac

mkdir -p "\${RESULT_DIR}"
cd "\${RESULT_DIR}"

java -jar "${APP_DIR}/load-tester.jar" \
  --write-target "\${WRITE_TARGET}" \
  --read-targets "\${READ_TARGETS}" \
  --write-ratio "\${WRITE_RATIO}" \
  --threads "\${THREADS}" \
  --duration "\${DURATION_SECONDS}" \
  --key-space "\${KEY_SPACE}" \
  \${QUIET_FLAG}
EOF
}

for index in 0 1 2 3 4; do
  deploy_kv_node "${index}"
done

deploy_load_tester

echo
echo "Deployment complete."
echo "Mode: ${MODE}"
echo "Leader public URL: http://${PUBLIC_IPS[0]}:8080"
echo "Leaderless ALB URL: ${LEADERLESS_ALB_URL}"
echo "Load tester SSH: ssh -i ${SSH_KEY} ${SSH_USER}@${LOAD_TESTER_PUBLIC_IP}"
