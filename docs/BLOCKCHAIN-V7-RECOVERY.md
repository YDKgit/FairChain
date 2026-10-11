# Blockchain v7 — guarded retry of a prepared block

## Scope
Only the `node` application was changed. The new endpoint is intentionally a **manual operator action**, not background reconciliation or an atomic distributed commit. It cannot prove a quorum decision and should only be invoked for an operation whose original approval and block hash have been independently confirmed.

## Request

`POST http://localhost:8081/node/consensus/recovery/{blockIndex}?blockHash={64-lowercase-hex}`

Required header: `X-Node-Secret: <configured secret>`.

Example PowerShell (replace the sample hash with the **actual saved** `pending_blocks.block_hash`):

```powershell
$secret = $env:NODE_SECRET
$hash = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/node/consensus/recovery/1?blockHash=$hash" -Headers @{ 'X-Node-Secret' = $secret }
```

Example positive response (only after successful confirmations):

```json
{"accepted":true,"blockIndex":1}
```

Example negative response (unknown proposal, mismatching hash, changed chain head, unavailable peer):

```json
{"accepted":false,"blockIndex":null}
```

A negative response **does not mean that no other node committed the block**. Always inspect all node statuses before retrying. HTTP transport errors can still cause partial commits.

## How it operates

1. Requires an existing `pending_blocks` row for the requested index.
2. Rejects a different hash and rejects a proposal whose previous hash / next index no longer match this node's chain.
3. Re-sends the same proposal to each peer, then requests idempotent confirmation of exactly the same block hash.
4. If every peer confirms, confirms locally. If any peer fails, leaves local `pending_blocks` unchanged.
5. Never overwrites an existing block or silently deletes an inconsistent proposal.

Important limitations: this is not PBFT, is not a general crash-recovery protocol, and cannot safely resolve conflicting proposals, concurrent leaders, split-brain, or database loss. Production safety requires persisted quorum certificates, leader/term or view management, durable decisions and deterministic catch-up.

## Read-only inspection on Windows

```powershell
.\scripts\check-three-nodes.ps1
```

It requires three running nodes on ports 8081–8083. The script checks availability, chain validity, pending proposals and identical ledger heads. A `CONSISTENT` result compares current status; it is not proof that a Byzantine consensus protocol has completed.

## Build and tests

```powershell
.\gradlew.bat :node:test :fairchain:test
.\gradlew.bat :node:bootJar :fairchain:bootJar
```

Integration testing still required: 3 real processes, separate databases, normal write, repeated write, failure between peer confirmations, restart and manual retry, and repeated verification. A test must assert that all three `headHash` values match and no prepared proposal remains.
