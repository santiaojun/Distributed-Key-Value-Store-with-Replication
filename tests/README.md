# tests

Integration and consistency tests can be stored in this folder when they are split from `kv-node/src/test`.

Planned coverage based on team plan:

- Leader-Follower strong consistency under `W=5`.
- Leader-Follower inconsistency window under `W=1`.
- Leaderless inconsistency window during coordinator propagation.
