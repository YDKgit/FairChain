# Blockchain API v13: durable decision delivery

> Demo protocol, **not** PBFT or atomic multi-node commit. 3/3 PREPARE is required.

## Node-to-node: persist COMMIT decision

`POST /node/consensus/blocks/{blockIndex}/decision?blockHash={64-lowercase-hex}`

Header: `X-Node-Secret: <configured secret>`

Body: none. Success response: `true`; failure response: `false`.

Precondition: a matching PREPARE record exists in `pending_blocks`. This endpoint stores `commitDecided=true` locally; it does not write the blockchain block yet. A mismatched hash or nonexistent proposal returns `false`.

## Node-to-node: apply decided COMMIT

`POST /node/consensus/blocks/{blockIndex}/confirm?blockHash={64-lowercase-hex}`

Requires matching pending block **and** persisted `commitDecided=true`. Then inserts block and deletes pending record. Repeated requests for the exact already committed block are idempotent.

## Operator: retry a decided proposal

`POST /node/consensus/recovery/{blockIndex}?blockHash={64-lowercase-hex}`

Only the coordinator with a locally persisted COMMIT decision may redeliver its decision and confirmations. No new block hash is generated. If peers are unreachable or disagree, the retry fails closed.

## Typical sequence

1. `POST /node/records` to the coordinator.
2. Coordinator persists PREPARE locally and asks its two peers for PREPARE.
3. After 3/3 positive PREPARE responses, coordinator persists `commitDecided=true`.
4. Coordinator sends `/decision` to both peers; each persists decision.
5. Coordinator sends `/confirm` to both peers, then confirms locally.
6. If partially applied, operator can retry using `/recovery` with identical block hash.

## Operational constraints

- No automated leader election or durable quorum certificate.
- Does not solve Byzantine nodes, split-brain coordinators, or rollback of conflicting histories.
- All three nodes must be reachable to complete the strict write.
- Shared secret is for local demo only; use TLS, distinct node credentials and rotation for deployment.
- Always run `:node:test :fairchain:test` and `scripts/integration-v8.py` before treating the implementation as working.
