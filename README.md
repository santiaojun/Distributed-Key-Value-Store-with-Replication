# CS6650 Assignment 4 — Distributed Key-Value Store with Replication



---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Testing](#testing)
- [Load Testing](#load-testing)
- [Deployment](#deployment)
- [Project Structure](#project-structure)
- [Team](#team)

---

## Overview

This project implements and benchmarks two replicated in-memory key-value store architectures to evaluate CAP-theorem trade-offs under configurable replication strategies.

| Architecture | Description |
|---|---|
| **Leader-Follower** | One leader accepts all writes and replicates to four followers. Supports W=5/R=1, W=3/R=3, and W=1/R=5 quorum configurations. |
| **Leaderless** | Any node accepts reads and writes. The receiving node becomes the write coordinator and propagates to all peers (W=N=5, R=1). |

Both systems run five nodes (N=5) and are deployed on AWS EC2 managed by Terraform.

### Simulated Delays

Artificial delays are applied to make the inconsistency window observable:

| Operation | Delay per node | W=5 total write latency |
|---|---|---|
| Write | 200 ms | ~1,000 ms |
| Read | 50 ms | ~50–250 ms depending on R |

---

## Architecture

### Leader-Follower

```
Client Writes                       Client Reads
     │                                   │
     ▼                                   ▼ (round-robin or any node)
┌──────────┐   replication    ┌──────────────────────────────────┐
│  Leader  │ ───────────────▶ │  Follower 2 │ 3 │ 4 │ 5         │
│  Node 1  │ (sequential,     └──────────────────────────────────┘
└──────────┘  up to W nodes)
```

- The leader assigns version numbers and is the sole entry point for client writes.
- Followers reject client-facing write requests (`503 Service Unavailable`).
- Reads can be served by any node; when R > 1 the read coordinator collects R responses and returns the highest version.

### Leaderless

```
         Client Reads & Writes
                  │
                  ▼
     ┌────────────────────────┐
     │  Application Load      │
     │  Balancer (ALB)        │
     └───────────┬────────────┘
      ┌──────────┼──────────┐
      ▼          ▼          ▼
  ┌───────┐  ┌───────┐  ┌───────┐
  │ Node 1│◀▶│ Node 2│◀▶│ Node 3│  ...Node 4, Node 5
  └───────┘  └───────┘  └───────┘
 (write coordinator propagates to all peers before acknowledging)
```

- Any node can be the write coordinator for a given request.
- Reads are always served locally (R=1), so a read before full propagation may return stale data.
- All writes require acknowledgment from all N=5 nodes before returning `201 Created`.

---

## Prerequisites

| Tool | Version |
|---|---|
| Java | 17+ |
| Maven | 3.8+ |
| Docker & Docker Compose | v2+ |
| Python | 3.9+ (for plot generation) |
| Terraform | 1.5+ (for AWS deployment) |
| AWS CLI | configured with credentials (for AWS deployment) |

---

## Getting Started

### Clone and Build

```bash
git clone https://github.khoury.northeastern.edu/odieyangkhoury/cs6650-assignment4.git
cd cs6650-assignment4
mvn clean package -DskipTests
```

### Run a Leader-Follower Cluster (Local)

```bash
docker compose up --build
```

Starts one leader on port `8080` and four followers on ports `8081`–`8084`.
Default quorum: W=5/R=1. Override at startup:

```bash
WRITE_QUORUM_SIZE=3 READ_QUORUM_SIZE=3 docker compose up --build
```

### Run a Leaderless Cluster (Local)

```bash
docker compose -f docker-compose.leaderless.yml up --build
```

Starts five peer nodes on ports `8080`–`8084`, each configured with the other four peers.

### Quick Smoke Test

```bash
# Write
curl -X PUT http://localhost:8080/kv \
  -H "Content-Type: application/json" \
  -d '{"key": "hello", "value": "world"}'

# Read from leader
curl http://localhost:8080/kv?key=hello

# Local read from follower (bypasses quorum)
curl http://localhost:8081/local_read?key=hello
```

---

## Configuration

All node behavior is controlled via environment variables.

### Leader-Follower

| Variable | Values | Description |
|---|---|---|
| `ROLE` | `leader` \| `follower` | Node role |
| `FOLLOWER_URLS` | comma-separated URLs | Follower addresses (leader only) |
| `WRITE_QUORUM_SIZE` | `1` \| `3` \| `5` | W — nodes that must acknowledge a write |
| `READ_QUORUM_SIZE` | `1` \| `3` \| `5` | R — nodes queried on a read |

### Leaderless

| Variable | Values | Description |
|---|---|---|
| `PEER_URLS` | comma-separated URLs | All other node addresses |

When `PEER_URLS` is set the node operates in Leaderless mode regardless of other variables.

---

## API Reference

All nodes expose the following HTTP endpoints.

### `PUT /kv` — Write

**Request**
```json
{ "key": "mykey", "value": "myvalue" }
```

**Response `201 Created`**
```json
{ "key": "mykey", "version": 1 }
```

**Response `503 Service Unavailable`** — quorum could not be reached.

---

### `GET /kv?key={key}` — Read

**Response `200 OK`**
```json
{ "key": "mykey", "value": "myvalue", "version": 1 }
```

**Response `404 Not Found`** — key does not exist on any queried node.

---

### `GET /local_read?key={key}` — Local Read (Testing Only)

Returns the value stored on the **receiving node only**, bypassing quorum logic and leader routing. Used in unit tests to probe consistency windows.

---

### Internal Endpoints (Node-to-Node Only)

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `/kv/internal` | Receive a replicated write from the leader or coordinator |
| `GET` | `/kv/internal?key={key}` | Return local value for a quorum read aggregation |

---

## Testing

```bash
mvn test
```

### Unit Test Coverage

| Test Class | Configuration | What It Verifies |
|---|---|---|
| `LeaderConsistencyTest` | W=5, R=1 | Read from leader returns latest version after write |
| `FollowerConsistencyTest` | W=5, R=1 | Read from any follower returns latest version after write; follower rejects client writes; stale version never overwrites newer version |
| `FollowerConsistencyTest` | W=1, R=1 | `local_read` on follower within propagation window returns stale data |
| `LeaderlessConsistencyTest` | W=5, R=1 | Coordinator write is consistent; reads during propagation window may be stale; reads after acknowledgment are consistent |

---

## Load Testing

The load tester (`load-tester/`) records per-request latency, version, and staleness for each operation and exports results as CSV.

### Key Design

- **Small key space** (default 100 keys) ensures reads and writes cluster on the same keys, making stale reads observable.
- **Per-key version tracking** — the client records the last write version per key and flags lower-version reads as stale.
- **Separate routing** — Leader-Follower writes target the leader; reads are distributed round-robin across all nodes.

### Run Against a Local Cluster

**Leaderless** (all requests via single target or ALB):
```bash
cd load-tester
java -jar target/load-tester-*.jar \
  --target http://localhost:8080 \
  --threads 32 \
  --duration 60 \
  --write-ratio 0.1 \
  --key-space 100
```

**Leader-Follower** (writes to leader, reads round-robin):
```bash
java -jar target/load-tester-*.jar \
  --write-target http://localhost:8080 \
  --read-targets http://localhost:8080,http://localhost:8081,http://localhost:8082,http://localhost:8083,http://localhost:8084 \
  --threads 32 \
  --duration 60 \
  --write-ratio 0.1 \
  --key-space 100
```

### CLI Options

| Flag | Default | Description |
|---|---|---|
| `--target` | `http://localhost:8080` | Default target for both reads and writes |
| `--write-target` | same as `--target` | Write endpoint (leader in LF mode) |
| `--read-targets` | same as `--target` | Comma-separated read endpoints (round-robin) |
| `--threads` | `32` | Concurrent worker threads |
| `--duration` | `60` | Test duration in seconds |
| `--write-ratio` | `0.1` | Fraction of operations that are writes (0.0–1.0) |
| `--key-space` | `100` | Number of distinct keys |
| `--quiet` | `false` | Suppress per-request log output |

### Generate Plots

```bash
cd load-tester
pip3 install -r requirements.txt
python3 plot.py
# Plots are written to results/yunhong-local/plots/
```

To plot a different results directory:
```bash
RESULTS_DIR=results/aws-final python3 plot.py
```

---

## Deployment

### Provision AWS Infrastructure

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
# Fill in: VPC ID, subnet IDs, AMI ID, key pair name, allowed CIDR
terraform init
terraform plan
terraform apply
```

**Provisioned resources:**

| Resource | Count | Purpose |
|---|---|---|
| EC2 `t3.micro` | 5 | KV nodes |
| EC2 `t3.micro` | 1 | Load tester |
| Application Load Balancer | 1 | Leaderless write/read distribution |
| Target group | 1 | All 5 KV nodes |
| Security groups | 3 | KV nodes, load tester, ALB |

**Key outputs:** leader URL, all KV node URLs, load tester public IP, Leaderless ALB URL.

### Deploy Application

```bash
./scripts/deploy-aws.sh
```

Builds jars, installs Java 17 on each instance, copies jars, and installs a `cs6650-kv-node` systemd service.

### Run the Full Experiment Matrix

```bash
./scripts/run-aws-experiments.sh
```

Runs all four configurations (W=5/R=1, W=3/R=3, W=1/R=5, Leaderless) at four write ratios (1%, 10%, 50%, 90%) for 600 seconds each. Results are written to `load-tester/results/aws-final/`.

### Tear Down

```bash
terraform destroy
```

> **Important:** stop or destroy all EC2 instances when not in use to avoid unnecessary AWS charges.

---

## Project Structure

```
cs6650-assignment4/
├── kv-node/                          # Spring Boot KV service (shared by all roles)
│   ├── src/main/java/
│   │   ├── controller/               # KVController — routes PUT /kv, GET /kv, local_read
│   │   ├── service/
│   │   │   ├── LeaderFollowerService # Leader write/read, follower replication handler
│   │   │   ├── LeaderlessService     # Coordinator write, peer propagation, local read
│   │   │   ├── KvStore               # Thread-safe ConcurrentHashMap + version counters
│   │   │   └── DelaySimulator        # Fixed 200 ms write / 50 ms read sleeps
│   │   ├── config/                   # ClusterProperties (env-var binding), AppConfig
│   │   └── model/                    # KVEntry, KVRequest, KVResponse, KVInternalRequest
│   ├── src/test/java/
│   │   ├── LeaderConsistencyTest
│   │   ├── FollowerConsistencyTest
│   │   └── LeaderlessConsistencyTest
│   └── Dockerfile
├── load-tester/
│   ├── src/main/java/
│   │   ├── LoadTesterApplication     # Thread pool, request loop, CSV export
│   │   ├── LoadTesterConfig          # CLI argument parsing
│   │   └── model/RequestResult       # Per-request result record
│   ├── results/
│   │   ├── yunhong-local/            # 60-second local baseline CSV files and plots
│   │   └── aws-final/                # 600-second EC2 experiment CSV files
│   ├── plot.py                       # Matplotlib latency and interval plots
│   └── requirements.txt
├── terraform/
│   ├── main.tf                       # EC2, ALB, target group, security groups
│   ├── variables.tf
│   └── outputs.tf
├── scripts/
│   ├── deploy-aws.sh                 # Build, copy, and start services on EC2
│   └── run-aws-experiments.sh        # Full 4×4 experiment matrix runner
├── docker-compose.yml                # Local 5-node Leader-Follower cluster
├── docker-compose.leaderless.yml     # Local 5-node Leaderless cluster
├── report.md                         # Experiment results and consistency analysis
└── README.md
```

---

