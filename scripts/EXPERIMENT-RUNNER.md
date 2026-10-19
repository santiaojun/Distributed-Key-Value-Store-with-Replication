# Experiment Runner — Design and Usage

This document explains the 600-second AWS experiment matrix: what it measures,
how the scripts are structured, and how to run or resume it.

---

## Table of Contents

- [What the Matrix Measures](#what-the-matrix-measures)
- [Script Overview](#script-overview)
- [Script Internals](#script-internals)
  - [deploy-aws.sh](#deploy-awssh)
  - [run-aws-experiments.sh](#run-aws-experimentssh)
  - [remote-runner.sh](#remote-runnersh)
- [How to Run](#how-to-run)
- [How to Resume After Interruption](#how-to-resume-after-interruption)
- [Monitoring Progress](#monitoring-progress)
- [Pulling Results Locally](#pulling-results-locally)
- [File Naming Convention](#file-naming-convention)

---

## What the Matrix Measures

The experiment crosses **4 database configurations** × **4 write ratios** =
**16 runs**, each lasting 600 seconds with 32 concurrent threads.

| Configuration | Mode | W | R | Expected write latency |
|---|---|---|---|---|
| `w5r1` | Leader-Follower | 5 | 1 | ~1,000 ms |
| `w3r3` | Leader-Follower | 3 | 3 | ~600 ms |
| `w1r5` | Leader-Follower | 1 | 5 | ~200 ms |
| `leaderless` | Leaderless | 5 | 1 | ~1,000 ms (real network) |

| Write ratio | Read ratio | Purpose |
|---|---|---|
| 1% | 99% | Read-heavy baseline |
| 10% | 90% | Light write mix |
| 50% | 50% | Balanced |
| 90% | 10% | Write-heavy stress |

Each run produces one CSV file with one row per request containing timestamp,
operation type, key, latency, version, and staleness flag.

---

## Script Overview

```
scripts/
├── deploy-aws.sh          # Builds jars, deploys to EC2, writes systemd unit files
├── run-aws-experiments.sh # Orchestrates the full 4×4 matrix from local machine
└── remote-runner.sh       # Self-contained runner for EC2; survives local shutdown
```

| Script | Runs on | Requires | Use when |
|---|---|---|---|
| `deploy-aws.sh` | Local | Terraform, Maven, SSH key | Initial setup or one-off redeploy |
| `run-aws-experiments.sh` | Local | Terraform, Maven, SSH key | Running the matrix while local stays on |
| `remote-runner.sh` | EC2 load tester | SSH key on the tester | Running the matrix detached from local |

---

## Script Internals

### deploy-aws.sh

**Purpose:** Build both jars, configure and start the KV service on all five EC2
nodes, and deploy the load tester jar with a helper script.

**Execution flow:**

```
1. Read Terraform outputs → get 5 KV public IPs, 5 KV private IPs, load tester IP, ALB URL
2. mvn clean package → build kv-node.jar and load-tester.jar
3. For each of the 5 KV nodes (parallel-safe, sequential in script):
   a. Install Java 17 if absent (sudo dnf install)
   b. SCP kv-node.jar to /opt/cs6650-a4/kv-node.jar
   c. Write /etc/systemd/system/cs6650-kv-node.service with role-specific env vars:
      - Node 0: ROLE=leader, FOLLOWER_URLS=<private IPs of nodes 1-4>
      - Nodes 1-4: ROLE=follower
      - Leaderless: PEER_URLS=<private IPs of all other nodes>
      - WRITE_QUORUM_SIZE, READ_QUORUM_SIZE from env
   d. sudo systemctl daemon-reload && sudo systemctl restart cs6650-kv-node
4. Deploy load tester:
   a. SCP load-tester.jar
   b. Write /opt/cs6650-a4/run-load-test.sh with hardcoded routing:
      - leader-follower: WRITE_TARGET=leader private IP, READ_TARGETS=all 5 private IPs
      - leaderless: WRITE_TARGET=ALB, READ_TARGETS=ALB
```

**Key design choices:**
- All inter-node communication uses **private IPs** to avoid AWS data transfer costs
  and public IP routing latency.
- The `run-load-test.sh` helper is regenerated on every deploy so its hardcoded
  targets always reflect the current infrastructure.
- Quorum sizes (W, R) and mode are passed as shell environment variables, so
  the same Docker image / JAR is reused for all configurations.

---

### run-aws-experiments.sh

**Purpose:** Orchestrate the full 16-run matrix from the local machine.

**Execution flow:**

```
For each config in [w5r1, w3r3, w1r5, leaderless]:
  1. Call deploy-aws.sh with MODE, WRITE_QUORUM_SIZE, READ_QUORUM_SIZE
     → redeploys all 5 KV nodes with the new quorum config
  2. For each ratio in [0.01, 0.10, 0.50, 0.90]:
     a. SSH into the load tester EC2
     b. Set RESULT_DIR, MODE, WRITE_RATIO, THREADS, DURATION_SECONDS, KEY_SPACE
     c. Execute /opt/cs6650-a4/run-load-test.sh (runs the Java load tester)
     d. Rename the output CSV to include the config name
     e. SCP the CSV back to load-tester/results/aws-final/<config>/
```

**Limitation:** the script runs as a foreground process on the local machine.
If the local machine sleeps or shuts down, SSH connections are broken and the
current load test run is lost. Use `remote-runner.sh` instead for long sessions.

---

### remote-runner.sh

**Purpose:** A self-contained version of the experiment matrix that runs
entirely on the EC2 load tester inside a `screen` session, so it continues
running even when the local machine is offline.

**How it differs from run-aws-experiments.sh:**

| Aspect | run-aws-experiments.sh | remote-runner.sh |
|---|---|---|
| Runs on | Local machine | EC2 load tester |
| KV deploy | Calls deploy-aws.sh (builds jars) | SSHes directly to KV private IPs and rewrites the systemd unit file |
| Jar rebuild | Yes (mvn package) | No (reuses the already-deployed JAR) |
| Result pull | SCP to local | Results stay on EC2; pulled separately |
| Survives local shutdown | No | Yes (inside screen) |

**Execution flow:**

```
1. For each config that still needs running:
   a. deploy_config():
      - For each of the 5 KV private IPs:
        i.  SSH to the node
        ii. Pipe a new systemd unit file via `sudo tee`
            (sets ROLE, FOLLOWER_URLS or PEER_URLS, WRITE_QUORUM_SIZE, READ_QUORUM_SIZE)
        iii. sudo systemctl daemon-reload && sudo systemctl restart cs6650-kv-node
      - sleep 12 s to let all Spring Boot instances finish startup
   b. run_test() × 4 ratios:
      - Sets env vars (RESULT_DIR, MODE, WRITE_RATIO, THREADS, DURATION_SECONDS, KEY_SPACE)
      - Calls /opt/cs6650-a4/run-load-test.sh
      - Renames the output CSV from results_wr<N>_threads32.csv
        to <config>_results_wr<N>_threads32.csv
      - Logs timestamp on START and DONE
```

**systemd unit update (the config-switch mechanism):**

Rather than re-copying the JAR (which doesn't change between configs), the
script only rewrites the `[Service]` section's `Environment=` lines and
restarts the daemon. This is ~10× faster than a full redeploy.

```
Leader-Follower node 0 (leader):
  ROLE=leader
  FOLLOWER_URLS=http://10.20.2.202:8080,...

Leader-Follower nodes 1–4 (followers):
  ROLE=follower
  (no FOLLOWER_URLS or PEER_URLS)

Leaderless all nodes:
  PEER_URLS=http://<other 4 private IPs>
  (no ROLE or FOLLOWER_URLS)
```

**Why screen?**

`screen` keeps the shell process alive on the remote host regardless of the
SSH connection state. When the local SSH session disconnects (intentionally
or due to network interruption), the `screen` session and everything running
inside it continue unaffected.

---

## How to Run

### Option A — From local (simple, stays local)

```bash
# Full 4×4 matrix from scratch
./scripts/run-aws-experiments.sh

# Override specific variables
CONFIGS="w3r3 w1r5" RATIOS="0.01 0.10" DURATION_SECONDS=120 \
  ./scripts/run-aws-experiments.sh
```

### Option B — Detached on EC2 (survives local shutdown)

```bash
# 1. Upload the key so the load tester can SSH to KV nodes
scp -i cs6650-a4-key.pem cs6650-a4-key.pem \
  ec2-user@18.204.230.145:/opt/cs6650-a4/cs6650-a4-key.pem

# 2. Upload the runner script
scp -i cs6650-a4-key.pem scripts/remote-runner.sh \
  ec2-user@18.204.230.145:/opt/cs6650-a4/remote-runner.sh

# 3. SSH in, start a detached screen session
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145
screen -S exp
bash /opt/cs6650-a4/remote-runner.sh | tee /opt/cs6650-a4/runner.log
# Ctrl-A D  to detach (process keeps running)
```

Or as a one-liner:
```bash
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145 \
  "screen -dmS exp bash -c \
   'bash /opt/cs6650-a4/remote-runner.sh > /opt/cs6650-a4/runner.log 2>&1'"
```

**Prerequisites for Option B:**
- The load tester security group must allow SSH (port 22) to the KV nodes
  security group (add once with AWS CLI or Terraform).
- The SSH key must be present at `/opt/cs6650-a4/cs6650-a4-key.pem` on the
  load tester.

---

## How to Resume After Interruption

If the script stops mid-run (SSH disconnect, script error), check which CSVs
already exist on the load tester:

```bash
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145 \
  "find /opt/cs6650-a4/results -name '*.csv' | sort"
```

Then edit `remote-runner.sh` to comment out the already-completed runs before
re-uploading and restarting.

Alternatively, set `CONFIGS` and `RATIOS` when calling
`run-aws-experiments.sh` to skip completed configurations:

```bash
# Resume from w1r5 onwards
CONFIGS="w1r5 leaderless" ./scripts/run-aws-experiments.sh
```

---

## Monitoring Progress

**Tail the log** (while the run is active):
```bash
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145 \
  "tail -f /opt/cs6650-a4/runner.log"
```

**Check the screen session is alive:**
```bash
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145 "screen -ls"
```

**Count completed runs:**
```bash
ssh -i cs6650-a4-key.pem ec2-user@18.204.230.145 \
  "find /opt/cs6650-a4/results -name '*_threads32.csv' | wc -l"
```

---

## Pulling Results Locally

After some or all runs complete, pull CSVs to the local results directory:

```bash
# Pull everything from all configs
for config in w5r1 w3r3 w1r5 leaderless; do
  mkdir -p load-tester/results/aws-final/$config
  scp -i cs6650-a4-key.pem \
    "ec2-user@18.204.230.145:/opt/cs6650-a4/results/$config/*.csv" \
    "load-tester/results/aws-final/$config/"
done
```

Then regenerate plots:
```bash
cd load-tester
RESULTS_DIR=results/aws-final python3 plot.py
```

---

## File Naming Convention

| Stage | File name pattern | Example |
|---|---|---|
| Raw output (load tester) | `results_wr<N>_threads<T>.csv` | `results_wr50_threads32.csv` |
| Renamed by runner | `<config>_results_wr<N>_threads<T>.csv` | `w3r3_results_wr50_threads32.csv` |
| Local (pulled) | `load-tester/results/aws-final/<config>/<above>` | `load-tester/results/aws-final/w3r3/w3r3_results_wr50_threads32.csv` |

The rename step adds the config prefix so that multiple runs can coexist in the
same directory without collisions, and so that `plot.py` can identify which
configuration each file belongs to via the filename prefix.
