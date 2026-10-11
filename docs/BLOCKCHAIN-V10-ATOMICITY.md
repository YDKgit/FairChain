# v10 — database-serialized ticket transitions (partial atomicity)

This iteration adds `ticket_heads` (unique client ID + record key) with a JPA `PESSIMISTIC_WRITE` lock, and a unique anchor index for `(client_server_id, recordKey, version)`.

`TicketWriteCoordinator.prepare()` creates and commits a PENDING row in its own Spring transaction **before** the remote call. `confirm()` commits CONFIRMED only after node acknowledgement. An incomplete remote call leaves PENDING and fails closed; it needs operator-led reconciliation.

**Limits:** This is NOT a distributed atomic transaction between FairChain and three Nodes. Unknown remote outcomes, duplicate in-flight submissions, automatic recovery, full concurrent HTTP integration tests, and real gate-auth/ownership checks remain open. Multiple FairChain instances must share one relational database. Do not treat this code as production-ready.

## Example

`POST /api/anchors` with `X-API-Key` and payload `{"type":"CHECKIN","eventId":10,"userId":5,"slotKey":"seat","recordKey":"<64 lowercase hex>","version":3,"recordHash":"<64 lowercase hex>"}`. A successful response includes `blockState: CONFIRMED`; unresolved writes return `PENDING`.

## Tests

Run `gradlew.bat :fairchain:test :node:test` on Windows; build and distributed integration results are not verified here.
