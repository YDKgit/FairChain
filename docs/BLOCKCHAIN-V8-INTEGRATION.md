# FairChain Blockchain v8 — Integration Runbook

## Scope
Only `node`, `node-api`, and the minimum FairChain blockchain integration are covered. No reservation/queue changes.

## Prerequisites
- JDK 17+ and a working Gradle 8.14 wrapper distribution.
- Python 3.9+ available as `python` / `py`.
- Ports 18080–18083 unused (override with `V8_BASE_PORT`).

## Windows (PowerShell)
```powershell
.\gradlew.bat :node:test :fairchain:test
.\gradlew.bat :node:bootJar :fairchain:bootJar
py -3 .\scripts\integration-v8.py
```

## Linux / macOS
```bash
./gradlew :node:test :fairchain:test
./gradlew :node:bootJar :fairchain:bootJar
python3 scripts/integration-v8.py
```

## What it checks
1. Starts 3 Node JVM processes and 1 FairChain JVM process with independent temporary H2 databases.
2. Empty-chain audit reports agreement.
3. Issues a record through FairChain and confirms it on all three nodes.
4. Verifies record through FairChain and individually on each node.
5. Rejects changed fingerprint.
6. Records CHECKIN version 2 and rejects version 1.
7. Checks that all nodes agree on height 2 and head hash.

This is a **happy-path** end-to-end test. It is not a failure-injection test, proof of atomic commit, or ticket ownership/QR verification.

## Known limits
- Some requests can partially commit. This project intentionally does not call the demo consensus PBFT.
- `recordHash` is supplied by the caller rather than computed from canonical ticket fields.
- `CHECKIN` is a versioned anchor event, not yet a complete anti-double-entry policy.
- Gradle compilation and this script must be executed to claim the system passes.
