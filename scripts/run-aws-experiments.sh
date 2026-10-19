#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_KEY="${SSH_KEY:-${ROOT_DIR}/cs6650-a4-key.pem}"
SSH_USER="${SSH_USER:-ec2-user}"
APP_DIR="${APP_DIR:-/opt/cs6650-a4}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/load-tester/results/aws-final}"
CONFIGS="${CONFIGS:-w5r1 w3r3 w1r5 leaderless}"
RATIOS="${RATIOS:-0.01 0.10 0.50 0.90}"
THREADS="${THREADS:-32}"
DURATION_SECONDS="${DURATION_SECONDS:-600}"
KEY_SPACE="${KEY_SPACE:-100}"

for command in terraform jq ssh scp; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Missing required command: ${command}" >&2
    exit 1
  fi
done

chmod 400 "${SSH_KEY}"
LOAD_TESTER_PUBLIC_IP="$(terraform -chdir="${ROOT_DIR}/terraform" output -raw load_tester_public_ip)"

ratio_label() {
  case "$1" in
    0.01|.01) echo "1" ;;
    0.10|0.1|.10|.1) echo "10" ;;
    0.50|0.5|.50|.5) echo "50" ;;
    0.90|0.9|.90|.9) echo "90" ;;
    *)
      echo "Unsupported ratio: $1" >&2
      exit 1
      ;;
  esac
}

deploy_config() {
  case "$1" in
    w5r1)
      MODE=leader-follower WRITE_QUORUM_SIZE=5 READ_QUORUM_SIZE=1 "${ROOT_DIR}/scripts/deploy-aws.sh"
      ;;
    w3r3)
      MODE=leader-follower WRITE_QUORUM_SIZE=3 READ_QUORUM_SIZE=3 "${ROOT_DIR}/scripts/deploy-aws.sh"
      ;;
    w1r5)
      MODE=leader-follower WRITE_QUORUM_SIZE=1 READ_QUORUM_SIZE=5 "${ROOT_DIR}/scripts/deploy-aws.sh"
      ;;
    leaderless)
      MODE=leaderless "${ROOT_DIR}/scripts/deploy-aws.sh"
      ;;
    *)
      echo "Unsupported config: $1" >&2
      exit 1
      ;;
  esac
}

mode_for_config() {
  case "$1" in
    leaderless) echo "leaderless" ;;
    w5r1|w3r3|w1r5) echo "leader-follower" ;;
    *)
      echo "Unsupported config: $1" >&2
      exit 1
      ;;
  esac
}

run_ratio() {
  local config="$1"
  local ratio="$2"
  local label
  local mode
  local remote_dir
  local raw_name
  local final_name
  local local_dir

  label="$(ratio_label "${ratio}")"
  mode="$(mode_for_config "${config}")"
  remote_dir="${APP_DIR}/results/${config}"
  raw_name="results_wr${label}_threads${THREADS}.csv"
  final_name="${config}_results_wr${label}_threads${THREADS}.csv"
  local_dir="${RESULTS_DIR}/${config}"

  mkdir -p "${local_dir}"

  echo "Running ${config} ratio=${ratio} duration=${DURATION_SECONDS}s threads=${THREADS}"
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 "${SSH_USER}@${LOAD_TESTER_PUBLIC_IP}" \
    "mkdir -p '${remote_dir}' && RESULT_DIR='${remote_dir}' MODE='${mode}' WRITE_RATIO='${ratio}' THREADS='${THREADS}' DURATION_SECONDS='${DURATION_SECONDS}' KEY_SPACE='${KEY_SPACE}' '${APP_DIR}/run-load-test.sh' && mv '${remote_dir}/${raw_name}' '${remote_dir}/${final_name}'"

  scp -i "${SSH_KEY}" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 \
    "${SSH_USER}@${LOAD_TESTER_PUBLIC_IP}:${remote_dir}/${final_name}" "${local_dir}/${final_name}"
}

for config in ${CONFIGS}; do
  deploy_config "${config}"
  for ratio in ${RATIOS}; do
    run_ratio "${config}" "${ratio}"
  done
done

echo "AWS experiment results saved under ${RESULTS_DIR}"
