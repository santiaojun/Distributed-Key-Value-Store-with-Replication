#!/usr/bin/env bash
# Runs entirely on the EC2 load tester.
# Deploys each KV config (via SSH to KV nodes) then runs load tests.
# Start inside screen: screen -S exp bash /opt/cs6650-a4/remote-runner.sh

set -uo pipefail

KEY=/opt/cs6650-a4/cs6650-a4-key.pem
APP_DIR=/opt/cs6650-a4
RESULTS_BASE=$APP_DIR/results
SSH_USER=ec2-user
THREADS=32
DURATION=600
KEY_SPACE=100
ALB_URL="http://cs6650-a4-leaderless-alb-979545484.us-east-1.elb.amazonaws.com"

KV_PRIVATE=(10.20.1.90 10.20.2.202 10.20.1.205 10.20.2.218 10.20.1.115)
FOLLOWER_URLS="http://10.20.2.202:8080,http://10.20.1.205:8080,http://10.20.2.218:8080,http://10.20.1.115:8080"

ssh_kv() {
  local ip=$1; shift
  ssh -i "$KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 "$SSH_USER@$ip" "$@"
}

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# ── Redeploy all 5 KV nodes with a new quorum config ──────────────────────────
deploy_config() {
  local config=$1
  local wq rq mode

  case $config in
    w5r1)      mode=leader-follower; wq=5; rq=1 ;;
    w3r3)      mode=leader-follower; wq=3; rq=3 ;;
    w1r5)      mode=leader-follower; wq=1; rq=5 ;;
    leaderless) mode=leaderless;     wq=5; rq=1 ;;
  esac

  log "Deploying config: $config (mode=$mode W=$wq R=$rq)"

  for i in 0 1 2 3 4; do
    local ip="${KV_PRIVATE[$i]}"
    local role follower_env peer_env

    if [[ $mode == leader-follower ]]; then
      if [[ $i -eq 0 ]]; then
        role=leader
        follower_env="Environment=FOLLOWER_URLS=$FOLLOWER_URLS"
        peer_env=""
      else
        role=follower
        follower_env=""
        peer_env=""
      fi
    else
      role=peer
      follower_env=""
      local peers=""
      for j in 0 1 2 3 4; do
        if [[ $j -ne $i ]]; then
          [[ -n $peers ]] && peers="$peers,"
          peers="${peers}http://${KV_PRIVATE[$j]}:8080"
        fi
      done
      peer_env="Environment=PEER_URLS=$peers"
    fi

    log "  node $((i+1)) ($ip) role=$role"

    ssh_kv "$ip" "sudo tee /etc/systemd/system/cs6650-kv-node.service > /dev/null" <<SVCEOF
[Unit]
Description=CS6650 Assignment 4 KV Node
After=network-online.target
Wants=network-online.target

[Service]
User=$SSH_USER
WorkingDirectory=$APP_DIR
Environment=PORT=8080
Environment=ROLE=$role
$follower_env
$peer_env
Environment=WRITE_QUORUM_SIZE=$wq
Environment=READ_QUORUM_SIZE=$rq
ExecStart=/usr/bin/java -jar $APP_DIR/kv-node.jar
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
SVCEOF

    ssh_kv "$ip" "sudo systemctl daemon-reload && sudo systemctl restart cs6650-kv-node"
  done

  log "Waiting 12 s for services to initialise..."
  sleep 12
}

# ── Run one load-test and rename the CSV ──────────────────────────────────────
run_test() {
  local config=$1
  local ratio=$2
  local label

  case $ratio in
    0.01) label=1  ;;
    0.10) label=10 ;;
    0.50) label=50 ;;
    0.90) label=90 ;;
  esac

  local mode result_dir raw final
  [[ $config == leaderless ]] && mode=leaderless || mode=leader-follower
  result_dir="$RESULTS_BASE/$config"
  raw="${result_dir}/results_wr${label}_threads${THREADS}.csv"
  final="${result_dir}/${config}_results_wr${label}_threads${THREADS}.csv"

  mkdir -p "$result_dir"
  log "START $config wr${label}% (${DURATION}s, ${THREADS} threads)"

  RESULT_DIR="$result_dir" \
  MODE="$mode" \
  WRITE_RATIO="$ratio" \
  THREADS="$THREADS" \
  DURATION_SECONDS="$DURATION" \
  KEY_SPACE="$KEY_SPACE" \
    "$APP_DIR/run-load-test.sh" && \
  mv "$raw" "$final" && \
  log "DONE  $config wr${label}% → $final" || \
  log "FAIL  $config wr${label}%"
}

# ══ Experiment matrix (remaining runs only) ════════════════════════════════════

log "=== remote-runner started ==="

# w5r1: wr1/wr10/wr50 done — only wr90 remaining
log "--- w5r1 (skip deploy, resume at wr90) ---"
run_test w5r1 0.90

# w3r3
log "--- w3r3 ---"
deploy_config w3r3
run_test w3r3 0.01
run_test w3r3 0.10
run_test w3r3 0.50
run_test w3r3 0.90

# w1r5
log "--- w1r5 ---"
deploy_config w1r5
run_test w1r5 0.01
run_test w1r5 0.10
run_test w1r5 0.50
run_test w1r5 0.90

# leaderless
log "--- leaderless ---"
deploy_config leaderless
run_test leaderless 0.01
run_test leaderless 0.10
run_test leaderless 0.50
run_test leaderless 0.90

log "=== ALL DONE ==="
