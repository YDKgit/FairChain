> **v5 notice:** `POST /node/consensus/blocks/{blockIndex}/confirm` now requires `?blockHash=<64-character lowercase SHA-256>`. See `BLOCKCHAIN-V5-STATUS.md` for the revised contract.

# FairChain Blockchain API — v4 (development)

This document describes the **actual controller interfaces** of this codebase, not a proposed future API. All examples use local development defaults. **Do not use these demo secrets outside localhost.** This version has NOT been verified by a successful Gradle build or end-to-end integration run in the authoring environment.

## Network and authentication

| Component | Default address | Who calls it |
|---|---|---|
| FairChain | `http://localhost:8080` | Example server (external client) |
| Node 1 | `http://localhost:8081` | FairChain / peer nodes |
| Node 2 | `http://localhost:8082` | FairChain / peer nodes |
| Node 3 | `http://localhost:8083` | FairChain / peer nodes |

FairChain endpoints require `X-API-Key`. Set `DEMO_API_KEY` before launch to bootstrap a sample client. Node endpoints require `X-Node-Secret`, default `local-demo-change-me` **for development only**; see `NodeAuthFilter`. All JSON fields are camelCase. The block fingerprint and record key are **lowercase SHA-256**, 64 hexadecimal characters. Versions are positive integers.

Below, sample values `aaaaaaaa...` and `bbbbbbbb...` are **placeholders** and not cryptographic hashes of a ticket. Use actual hashes derived by a trusted client/service. For valid demonstrations use `Sha256RecordHasher`, whose encoding length-prefixes fields.

## Public FairChain API

### 1. Create anchor / write blockchain

`POST /api/anchors` — header `X-API-Key: <configured api key>`; JSON:

```json
{
  "type": "ISSUE",
  "eventId": 10,
  "userId": null,
  "slotKey": "A-12",
  "recordKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "version": 1,
  "recordHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
}
```

`type` is from `RecordType` (see enum source; values may be `ISSUE`, `PURCHASE`, `CANCEL`, `CHECKIN`). The controller currently returns **the JPA `AnchorRecord` entity directly**. Representative success response (fields shown are illustrative; timestamps/IDs are assigned at runtime):

```json
{
  "id": 1,
  "clientServerId": "demo-client",
  "eventId": 10,
  "userKey": null,
  "recordKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "version": 1,
  "recordHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "type": "ISSUE",
  "blockState": "CONFIRMED",
  "blockIndex": 1,
  "createdAt": "2026-10-11T20:00:00"
}
```

`blockState` may instead be `PENDING` and `blockIndex` null. **PENDING does not prove blockchain commit and must not be treated as success.** An invalid API key gives HTTP `401`. Bad input and outdated versions throw `IllegalArgumentException`; no stable application-level error schema is implemented. **The current write protocol may partially commit on failures.**

### 2. Ticket / anchor verification (detailed)

`POST /api/anchors/tickets/verify` — header `X-API-Key: <key>`:

```json
{
  "recordKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "version": 1,
  "recordHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
}
```

Success response:

```json
{"valid":true,"reason":"VERIFIED","blockIndex":1}
```

Possible negative responses:

```json
{"valid":false,"reason":"ANCHOR_MISMATCH","blockIndex":1}
```

`reason` values from implementation:

| Reason | Meaning |
|---|---|
| `VERIFIED` | Stored FairChain anchor matches request AND all three nodes verify |
| `INVALID_INPUT` | Bad/missing fields or malformed hashes |
| `ANCHOR_NOT_FOUND` | No latest anchor for this API client and record key |
| `ANCHOR_NOT_CONFIRMED` | Latest anchor is not CONFIRMED |
| `ANCHOR_MISMATCH` | Version/hash differ from FairChain's latest anchor |
| `NODE_VERIFICATION_FAILED` | One or more nodes rejected or were unreachable (NodeClient currently converts errors to false) |
| `NODE_UNAVAILABLE` | Unexpected exception escaping node verification |

**Security boundary:** this endpoint compares a hash supplied by its caller to FairChain's stored hash and the chain. It does not reconstruct ticket data from trusted ticket fields or check user ownership, expiration, cancellation, or admission state. A hash alone is not evidence that a ticket was legitimately issued.

### 3. Legacy boolean verification

`POST /api/anchors/verify` uses the **same JSON body** and header as above. Response body is just `true` or `false`. Prefer `/api/anchors/tickets/verify` for a reason field.

## Internal Node API (not public)

All Node calls use `X-Node-Secret: <NODE_SECRET>`.

### 4. Propose recording on coordinator node

`POST http://localhost:8081/node/records`:

```json
{"recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
```

Example response if all three confirmations complete:

```json
{"accepted":true,"blockIndex":1}
```

Failure response from service (not guaranteed for HTTP errors):

```json
{"accepted":false,"blockIndex":null}
```

### 5. Verify latest record at an individual Node

`POST http://localhost:8081/node/records/verify`, same JSON fields as item 4:

```json
{"valid":true,"latestVersion":1,"latestRecordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
```

If no record exists: `{"valid":false,"latestVersion":null,"latestRecordHash":null}`. A stale version or modified fingerprint gives `valid:false`, with latestVersion/latestRecordHash filled from node state. v4 validates the chain from genesis **through the current HEAD** before returning true.

### 6. Batch verification on one Node

`POST http://localhost:8081/node/records/verify-batch`:

```json
{
  "records": [
    {"blockIndex":1,"recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
  ]
}
```

Illustrative response:

```json
{"results":[{"blockIndex":1,"matched":true,"confirmed":true}]}
```

`matched`: provided record fields equal fields stored at index. `confirmed`: the **entire locally stored chain** passes hash/integrity validation. Batch endpoint is per-node and does not execute quorum itself.

### 7. Peer consensus (internal; do NOT expose publicly)

`POST /node/consensus/proposals` with a full `BlockProposalDto`:

```json
{
  "blockIndex": 1,
  "prevBlockHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "recordKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "version": 1,
  "recordHash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "timestamp": 1791720000000,
  "blockHash": "<must be computed by Sha256RecordHasher>"
}
```

Response: JSON boolean `true`/`false`. `blockHash` must match the exact canonical hashing in `NodeService.hash()`; this example is illustrative, NOT a valid proposal ready to submit. `POST /node/consensus/blocks/{blockIndex}/confirm` has no JSON body and returns a boolean. These endpoints are intended for coordinator/peer communication, not direct user actions.

## PowerShell — copyable requests on Windows

Set your local environment and start three nodes and FairChain first. Set the API key to the value used in `DEMO_API_KEY`.

```powershell
$apiKey = 'YOUR_DEMO_API_KEY'
$body = @{
  recordKey = 'a' * 64
  version = 1
  recordHash = 'b' * 64
} | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:8080/api/anchors/tickets/verify' -Method Post -Headers @{'X-API-Key'=$apiKey} -ContentType 'application/json' -Body $body
```

To test one node (internal endpoint):

```powershell
Invoke-RestMethod -Uri 'http://localhost:8081/node/records/verify' -Method Post -Headers @{'X-Node-Secret'='local-demo-change-me'} -ContentType 'application/json' -Body $body
```

Do not treat these placeholder values as a successfully issued ticket. Create a real anchor first, then use its stored key/version/hash.

## Limitations and planned work

- This is a **development MVP**, not PBFT or Byzantine fault tolerance. The proposal+confirm flow can create partial commits on network errors. There is no recovery/reconciliation protocol.
- `X-Node-Secret` is a shared demo secret, not mutual TLS or node identity authentication; use a protected internal network.
- No trusted canonical ticket payload / issuance signature yet. QR values must never be trusted by themselves for admission.
- No rollback / idempotent recovery transaction for PENDING FairChain anchors.
- For 100k+ records, the v4 full-chain verification per request is O(N); checkpointing and cached validated HEADs are required for production-scale throughput.
- Run automated unit tests and a four-process integration test before claiming production readiness.

## v7: guarded manual retry (internal Node API)

`POST /node/consensus/recovery/{blockIndex}?blockHash=<64-lowercase-hex>`

Header: `X-Node-Secret: <node-secret>`. Request body: none.

Response on complete confirmation: `{"accepted":true,"blockIndex":1}`.
Response when not completed: `{"accepted":false,"blockIndex":null}`.

Requires the same prepared proposal to exist locally and the chain head to remain unchanged. It tries idempotent confirmations of the identical hash on both peers, then locally. **Not an atomic transaction or a safe conflict-resolution mechanism.** See `docs/BLOCKCHAIN-V7-RECOVERY.md`.
