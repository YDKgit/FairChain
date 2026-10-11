# Blockchain v3 — Ticket verification

## Changes from v2

* `fairchain`: `POST /api/anchors/tickets/verify` compares the incoming `(recordKey,version,recordHash)` with the **latest anchor owned by the authenticated client**, requires `CONFIRMED` and a non-null `blockIndex`, then verifies against all configured blockchain nodes. Responds `{valid,reason,blockIndex}`.
* Existing `POST /api/anchors/verify` now uses the same local+remote checks but returns boolean for backward compatibility.
* `NodeClient` requires all **three** valid node responses; node unavailability or inconsistent hashes fails closed. This matches the design document's demo assumption that all 3 nodes are healthy, but is **not** fault-tolerant quorum behavior.
* Added `AnchorServiceTest` negative and positive paths; fixed null checking for a node record submit and proposal index comparison.

## Verification with real ticket data

`X-API-Key` must correspond to a client registered with FairChain. Example values below are only placeholders: actual `recordKey` and `recordHash` should be produced by the ticket/voucher canonical record-hashing procedure and stored in the AnchorRecord.

```
POST http://localhost:8080/api/anchors/tickets/verify
X-API-Key: <client-key>
Content-Type: application/json

{"recordKey":"<64-lowercase-hex>","version":1,"recordHash":"<64-lowercase-hex>"}
```

Successful response: `{ "valid": true, "reason": "VERIFIED", "blockIndex": 1 }`.

Possible failures: `INVALID_INPUT`, `ANCHOR_NOT_FOUND`, `ANCHOR_NOT_CONFIRMED`, `ANCHOR_MISMATCH`, `NODE_VERIFICATION_FAILED`, `NODE_UNAVAILABLE`.

### Run tests

Windows: `gradlew.bat :node:test :fairchain:test`
Linux/macOS: `./gradlew :node:test :fairchain:test`

## Known limitations — IMPORTANT

1. V2's consensus/commit remains unsafe in partial network failure: one or two followers may commit even when leader returns unsuccessful. No claim of atomic 3-node commit or PBFT. Do **not** use for real-value tickets.
2. Ticket verification currently verifies a provided canonical hash against the latest FairChain AnchorRecord and blockchain nodes. It does **not** independently reconstruct the record hash from full voucher fields, enforce ticket ownership/status/expiry, or perform atomic check-in. Those require agreed ticket data contract with teammates.
3. Node verification checks complete chain history before the block but cannot establish trust in an attacker-controlled database without independent checkpoints or signatures.
4. Development uses H2 with separate per-node files; production requires stronger API authentication, authenticated peer identities, durable consensus log, recovery and MySQL migrations.
5. `NodeClient` requests do not yet have explicit per-node timeouts; a stalled node can cause slow verification.
6. Build/test execution was not completed here because Gradle distribution download was blocked by network restrictions. The tests are included but **not claimed passing**.
