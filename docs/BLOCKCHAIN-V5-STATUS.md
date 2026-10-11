# Blockchain v5 — status and API changes

## Scope
Only `node`, `node-api`, and integration scripts have changed. `fairchain` retains the v4 behavior.

## Improvement: durable PREPARE records
`POST /node/consensus/proposals` persists a proposal in `pending_blocks` before acknowledging it. Duplicate proposals with identical data are idempotent; conflicting proposals at the same index are rejected. A new write is rejected if unresolved prepared proposals remain on its local node.

## Changed confirmation contract (breaking change)
`POST /node/consensus/blocks/{blockIndex}/confirm?blockHash=<64 lowercase hex>`

Request body: none. Header: `X-Node-Secret`.
- `true`: matching proposal was stored in the blockchain, or the block was previously committed with exactly the same hash.
- `false`: proposal is missing, its hash differs, or it does not extend the local chain.

Example:
```powershell
$secret = 'local-demo-change-me'
$hash = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
Invoke-RestMethod -Method Post -Uri "http://localhost:8082/node/consensus/blocks/1/confirm?blockHash=$hash" -Headers @{'X-Node-Secret'=$secret}
```
The example returns `false` unless the matching proposal was previously prepared.

## Important limitations
- Current sequence remains: coordinator receives votes, asks peers to commit, then saves locally. A network failure after a peer commits can still leave different node states.
- There is no rollback, durable coordinator decision log, replay/reconciliation protocol, leader election or Byzantine fault tolerance. The implementation is **not PBFT** and not production-ready consensus.
- Pending proposals survive process restart, but an interrupted proposal needs a recovery/admin mechanism. Until then, this node intentionally refuses new writes instead of silently forking its chain.
- Endpoint is protected by one shared demo secret; use separate mTLS/identity and TLS in production.

## Local smoke test
1. `./gradlew :node:test :fairchain:test`
2. `./gradlew :node:bootJar :fairchain:bootJar`
3. Windows PowerShell: `./scripts/run-three-nodes.ps1`
4. Start FairChain on 8080 (see `README-IMPLEMENTATION.md`).
5. Submit one anchor through FairChain as documented in `docs/BLOCKCHAIN-API.md`, then verify it; inspect each node's local database. Three-node end-to-end success is **not yet verified**.
