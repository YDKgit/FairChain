# Blockchain v11 — Proof-based PENDING reconciliation

## Goal
If a request to Blockchain Node timed out *after* the nodes committed, FairChain can keep an anchor in `PENDING`. This version allows a manual acknowledgement **only** when all three nodes return matching, valid proofs. It never broadcasts a new block and never rewrites existing blocks.

## Internal API
`GET /node/records/proof/{recordKey}` (header `X-Node-Secret`). Example:

```json
{"valid":true,"blockIndex":12,"version":2,"recordHash":"<64 lowercase hex>","blockHash":"<64 lowercase hex>"}
```

Proof is rejected when the full local chain fails integrity validation. This is not a cryptographic signature and is trusted only over authenticated internal transport.

## FairChain API
`POST /api/anchors/tickets/{recordKey}/reconcile` (header `X-API-Key`). Request body: none.

Possible responses:

```json
{"valid":true,"reason":"RECONCILED","blockIndex":12}
{"valid":false,"reason":"PROOF_NOT_UNANIMOUS","blockIndex":null}
{"valid":false,"reason":"RECONCILIATION_CONFLICT","blockIndex":null}
```

FairChain checks the latest anchor for the authenticated client, its `PENDING` state, matching ticket version/hash on all three nodes, the same block index/hash, and applies the status change under the ticket-head database lock.

## Limitations
- Nodes with unequal heads or temporarily unavailable peers will not reconcile. No automatic retry.
- No atomic distributed commit or distributed leader election.
- Cross-service identity, event/owner validation, ticket signature and check-in authorization remain outside this change.
- Node proofs do not cryptographically bind the event type; it is verified from the FairChain anchor only.
- Unit/integration tests and real Java compilation are required before deployment.
