# Blockchain v9 — Ticket lifecycle (MVP)

## Scope
Only Blockchain Node and the necessary FairChain anchoring integration. Other reservation and gate services are not changed.

**Important:** This is a source-code implementation; Java/JUnit and 4-process integration tests have not yet been successfully run in this environment.

## Transition model

| Previous confirmed event | Next allowed event | New version |
|---|---|---|
| (none) | ISSUE | 1 |
| ISSUE | PURCHASE | 2 |
| PURCHASE | CANCEL or CHECKIN | previous + 1 |
| CANCEL | PURCHASE | previous + 1 |
| CHECKIN | none | terminal |

The same `(clientId, recordKey, type, version, recordHash)` is treated as an idempotent replay in FairChain. Later events are refused if the previous anchor remains `PENDING`. This MVP protects against concurrent requests only within a **single** FairChain process; add a DB-backed per-key lock and unique `(clientServerId, recordKey, version)` constraint for multiple replicas. No automated reconciliation of PENDING after partial Node commit is implemented.

## API: POST /api/anchors

Headers: `X-API-Key: <client key>`, `Content-Type: application/json`.

Example ISSUE:
```json
{"type":"ISSUE","eventId":42,"userId":null,"slotKey":"A-10","recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
```

Example PURCHASE uses the same `recordKey`, `eventId`, `slotKey`, but `type=PURCHASE`, `version=2`, and a separately computed corresponding `recordHash`.

Example CHECKIN uses `type=CHECKIN`, `version=3`, and its own `recordHash`. To cancel instead, use `type=CANCEL`, `version=3`.

Response is the saved `AnchorRecord`. The caller **must inspect** `blockState=CONFIRMED` and `blockIndex`; HTTP 200 with `PENDING` does not indicate committed blockchain state.

## API: GET /api/anchors/tickets/{recordKey}/status

Header: `X-API-Key: <client key>`.

Example successful response (only PURCHASE can be `valid=true`):
```json
{"valid":true,"reason":"VALID","state":"PURCHASE","version":2,"blockIndex":2}
```

Example after CHECKIN:
```json
{"valid":false,"reason":"ALREADY_CHECKED_IN","state":"CHECKIN","version":3,"blockIndex":3}
```

Failure reasons include `INVALID_INPUT`, `ANCHOR_NOT_FOUND`, `ANCHOR_NOT_CONFIRMED`, `NODE_UNAVAILABLE`, and `NODE_VERIFICATION_FAILED`. A confirmed ISSUE returns `NOT_PURCHASED`; CANCEL returns `CANCELLED`.

## API: POST /api/anchors/tickets/verify

Submit `{recordKey,version,recordHash}` and `X-API-Key`. The result is `{valid,reason,blockIndex}`. `VERIFIED` now means **current PURCHASE state plus three matching Node verifications**. Old versions fail `ANCHOR_MISMATCH`; CANCEL/CHECKIN become invalid even if their blockchain hashes match.

## Explicit limitations

- `recordHash` is supplied by caller and not yet recomputed from canonical ticket payload. This proves consistency with the anchored hash, **not authenticity of the user's claimed seat/owner**. Canonical hashing and authoritative voucher lookup must be integrated with the team's existing ticket service.
- No QR signature, gate staff authentication, reservation ownership checks, or transactional double-scan prevention is implemented here. A separate atomic gate admission workflow is required.
- No database-level cross-replica lock; no full crash-safe distributed commit; every write and status check must fail closed during inconsistency.
- The node ledger is immutable and only stores opaque hash/version/key. ISSUE/PURCHASE/CANCEL/CHECKIN are stored as metadata in FairChain AnchorRecord.
