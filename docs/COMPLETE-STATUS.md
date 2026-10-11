# Blockchain delivery status (v13 candidate)

## Actual verified scope
- Added durable `commitDecided` in `pending_blocks`.
- Coordinator persists COMMIT decision after 3/3 PREPARE before sending confirmations.
- Participant CONFIRM refuses undecided PREPARE.
- Manual recovery repeats only the persisted decision; no unilateral commit for mere PREPARE.

## Critical remaining limitations
- NOT a formally proven distributed consensus protocol; leader election, fencing, replication of a decision certificate and Byzantine tolerance are not implemented.
- A decision broadcast can partially reach peers; retry recovery is necessary. The local decision is not a majority-signed certificate, so this protocol is **not** Byzantine fault tolerant.
- Tests/build not run in restricted environment.

## Code review and build validation
Source-level changes were made; Gradle/JUnit execution was not available in this container. Do not call this release tested.
