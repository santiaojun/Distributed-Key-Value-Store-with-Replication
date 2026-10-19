# CS6650 Assignment 4 — Project Plan

> Northeastern University · CS6650 Building Scalable Distributed Systems

---

## Table of Contents

- [Project Overview](#project-overview)
- [System Design](#system-design)
- [Team and Responsibilities](#team-and-responsibilities)
- [Module Specifications](#module-specifications)
- [API Contract](#api-contract)
- [Development Workflow](#development-workflow)
- [Risk Register](#risk-register)
- [Delivery Milestones](#delivery-milestones)

---

## Project Overview

Build two replicated in-memory key-value stores (Leader-Follower and Leaderless)
to demonstrate CAP-theorem trade-offs. Measure consistency, latency, and
throughput across four quorum configurations and four read/write ratios using a
purpose-built load tester deployed on AWS EC2.

---

## System Design

### Modes

| Dimension | Leader-Follower | Leaderless |
|---|---|---|
| Node count | 5 (1 leader + 4 followers) | 5 peer nodes |
| Write entry point | Leader only | Any node (via ALB) |
| Read entry point | Any node | Any node (via ALB) |
| Version assignment | Leader | Write coordinator |
| Tested configurations | W=5/R=1, W=1/R=5, W=3/R=3 | W=N=5, R=1 |

### Delay Model

Delays are intentional and fixed to make quorum size visible in measured latency.

| Operation | Delay per node | Derived latency |
|---|---|---|
| Write | 200 ms | W × 200 ms (sequential) |
| Read | 50 ms | R × 50 ms (sequential) |

### Core Data Model

```java
// Per-entry storage record
record KVEntry(String value, int version) {}

// Thread-safe store with per-key atomic version counters
ConcurrentHashMap<String, KVEntry>   store    = new ConcurrentHashMap<>();
ConcurrentHashMap<String, AtomicInteger> versions = new ConcurrentHashMap<>();
```

Version rules:
- A key's first write assigns version 1.
- Each subsequent write increments the version.
- Incoming replication writes are rejected if their version is lower than the locally stored version (`putWithVersion` guard).

---

## Team and Responsibilities

| Name | Module | Primary Deliverables |
|---|---|---|
| Yunhong Huang | Load Tester | Client, CSV export, staleness detection, plots |
| Qingyu Cheng | KV Node Core | Storage, Leader-Follower write/read paths, internal endpoints |
| Runxin Shao | Leaderless + Tests | Leaderless coordinator, peer propagation, all unit tests |
| Ziqi Yang | Infrastructure + Integration | Terraform, Docker, deployment scripts, final hardening, report |

### Integration Hardening (Ziqi Yang)

Ziqi Yang owns the cross-module integration pass after each module's baseline is merged:

- Leader-Follower: quorum failure `503` response, stale-version overwrite protection, follower write rejection.
- Leaderless: full-acknowledgment semantics, peer failure `503` response.
- Load tester: Leader-Follower write-to-leader routing, round-robin read distribution, stale-ratio reporting.
- Deployment: Docker Compose reconciliation, Terraform security group hardening, experiment runner.

---

## Module Specifications

### Module 0 — Load Tester (Yunhong Huang)

**Goal:** Produce reproducible per-request latency and staleness measurements
across all configurations and write ratios.

**Requirements:**

1. Use a fixed key space of 100–500 keys to maximize same-key contention.
2. Support write ratios of 1%, 10%, 50%, and 90% via CLI flag `--write-ratio`.
3. Detect stale reads by tracking `lastWrittenVersion` per key and comparing against each read response version.
4. For Leader-Follower: route writes to `--write-target` (leader) and distribute reads round-robin across `--read-targets`.
5. For Leaderless: route all requests to the ALB endpoint via `--target`.
6. Record per-request: timestamp, type, key, latency, version, stale flag.
7. Export results as CSV on exit.

**Result record:**
```java
record RequestResult(String type, String key, long latencyMs,
                     int version, boolean isStale, long timestamp) {}
```

---

### Module A — KV Node Core and Leader-Follower (Qingyu Cheng)

**Goal:** Implement the shared KV storage engine and the Leader-Follower
replication protocol.

**Requirements:**

1. Thread-safe in-memory store using `ConcurrentHashMap` with per-key `AtomicInteger` version counters.
2. `PUT /kv`: apply write delay, increment version on leader, replicate sequentially to W−1 followers, return `201` only if all required acknowledgments received; otherwise `503`.
3. `GET /kv`: apply read delay on leader, query R−1 followers if R > 1, return the response with the highest version.
4. `GET /local_read`: return local value immediately with read delay, no routing.
5. `PUT /kv/internal` and `GET /kv/internal`: follower/peer endpoints for replication and quorum reads.
6. Follower nodes must reject client-facing `PUT /kv` with `503`.
7. Runtime configuration via `ROLE`, `FOLLOWER_URLS`, `WRITE_QUORUM_SIZE`, `READ_QUORUM_SIZE`.
8. Sequential (not parallel) replication to keep quorum cost visible in latency.

---

### Module B — Leaderless and Unit Tests (Runxin Shao)

**Goal:** Implement the Leaderless write-coordinator protocol and the full unit
test suite.

**Requirements:**

**Leaderless:**
1. Any node receiving `PUT /kv` becomes the write coordinator for that request.
2. Coordinator applies local write with 200 ms delay and assigns the next version.
3. Coordinator sequentially propagates to all four peers and awaits each acknowledgment.
4. Return `201` only after all four peers acknowledge; otherwise `503`.
5. `GET /kv` returns the local value after 50 ms delay (R=1, no cross-node reads).
6. Runtime configuration via `PEER_URLS`.

**Unit Tests (`@SpringBootTest` with `TestRestTemplate`):**

| Test | Configuration | Verification |
|---|---|---|
| Leader strong consistency (Test A) | W=5, R=1 | Read from leader after acknowledged write returns new version |
| Follower strong consistency (Test B) | W=5, R=1 | `local_read` from any follower after acknowledged write returns new version |
| Inconsistency window (Test C) | W=1, R=1 | `local_read` from follower during propagation window returns stale data or 404 |
| Leaderless inconsistency window | W=5, R=1 | Read from non-coordinator during propagation returns stale data |
| Leaderless post-ack consistency | W=5, R=1 | Read from coordinator and from peer after acknowledgment both return new version |

---

### Module C — Infrastructure, Deployment, and Report (Ziqi Yang)

**Goal:** Deliver production-ready infrastructure, deployment automation, and a
complete experiment report.

**Requirements:**

**Docker:**
1. Single Dockerfile for the KV node; role is controlled entirely by environment variables.
2. `docker-compose.yml` for five-node Leader-Follower local cluster.
3. `docker-compose.leaderless.yml` for five-node Leaderless local cluster.

**Terraform (AWS, us-east-1):**
4. Five KV node EC2 instances (`t3.micro`).
5. One load tester EC2 instance (`t3.micro`).
6. Application Load Balancer + target group (all five KV nodes) for Leaderless mode.
7. Security groups: KV nodes ↔ KV nodes, load tester → KV nodes, ALB → KV nodes, SSH from authorized CIDR.

**Deployment scripts:**
8. `scripts/deploy-aws.sh` — build jars, install Java 17 if absent, deploy jars and systemd services.
9. `scripts/run-aws-experiments.sh` — run all 4 × 4 = 16 experiment configurations (600 s each) and pull CSVs locally.

**Report (`report.md`):**
10. Per-configuration latency distribution plots (reads and writes separately).
11. Per-configuration same-key read-after-write interval distribution plots.
12. Per-write-ratio result tables for all four configurations.
13. Analysis: latency trade-offs, stale-read behavior, and application guidance.

---

## API Contract

All nodes expose the following endpoints. This contract is shared across both
architecture modes and must not be modified without team agreement.

| Method | Endpoint | Request Body | Success Response | Error Response |
|---|---|---|---|---|
| `PUT` | `/kv` | `{"key":"k","value":"v"}` | `201 {"key":"k","version":N}` | `503` quorum not met |
| `GET` | `/kv?key=k` | — | `200 {"key":"k","value":"v","version":N}` | `404` not found |
| `GET` | `/local_read?key=k` | — | `200 {"key":"k","value":"v","version":N}` | `404` not found |
| `PUT` | `/kv/internal` | replication payload | `200` | `503` if error |
| `GET` | `/kv/internal?key=k` | — | `200 {"version":N,"value":"v"}` | `404` not found |

---

## Development Workflow

1. **Branch strategy:** use `main` with path-based ownership to minimize merge conflicts.
2. **Path ownership:**
   - `load-tester/` — Module 0 for baseline; Module C during final integration.
   - `kv-node/` — Modules A and B for baseline; Module C during final integration.
   - `terraform/`, `scripts/`, Docker Compose files — Module C only.
3. **Rebase policy:** run `git pull --rebase` before every push.
4. **API changes:** require team agreement before modifying any shared endpoint contract.
5. **Integration pass:** Module C merges and hardens all modules after each baseline is stable; no module owner may revert integration changes without team discussion.

---

## Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Parallel replication invalidates expected quorum latency | Medium | High | Enforce sequential replication; verify end-to-end latency in tests |
| 2 | Non-atomic version increments cause incorrect ordering | Medium | High | Use per-key `AtomicInteger`; guard incoming writes with `putWithVersion` |
| 3 | Stale-window unit tests miss inconsistency due to timing | High | Medium | Issue immediate concurrent `local_read` calls during propagation window |
| 4 | Large key space reduces stale-read observability | Low | Medium | Cap key space at 500 keys (default 100) |
| 5 | AWS cost overrun from idle EC2 instances | Medium | Low | Run `terraform destroy` or stop instances after each session |

---

## Delivery Milestones

| Phase | Deliverable | Owner |
|---|---|---|
| 1 | Load tester baseline with CSV export and stale-read detection | Yunhong Huang |
| 2 | KV node with Leader-Follower write/read paths and internal endpoints | Qingyu Cheng |
| 3 | Leaderless coordinator and full unit test suite | Runxin Shao |
| 4 | Terraform infrastructure, Docker Compose files, deploy scripts | Ziqi Yang |
| 5 | Integration hardening pass (503 behavior, staleness guards, multi-node routing) | Ziqi Yang |
| 6 | Full AWS experiment matrix (600 s × 4 configs × 4 ratios) | Ziqi Yang |
| 7 | Final report with plots, analysis, and submission package | Ziqi Yang |
