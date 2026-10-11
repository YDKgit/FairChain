# Blockchain verification MVP — local validation

Scope: node + node-api + FairChain blockchain adapter only. This is not production consensus.

## Build and run

Requirements: JDK 17+ and network access for first Gradle bootstrap.

```bash
./gradlew :node:test :fairchain:test
./gradlew :node:bootJar :fairchain:bootJar
```

Then start 3 nodes and FairChain using the commands in `README-IMPLEMENTATION.md`.

## Manual API smoke test

POST to `http://localhost:8080/api/anchors` with `X-API-Key: local-demo-key` and:

```json
{"type":"ISSUE","eventId":1,"recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
```

Use `POST /api/anchors/verify` with the same key/version/hash. Expect true only after records were confirmed.

### Negative tests

1. Change `recordHash` to 64 `c` characters: verification must return false.
2. Change `version` to 2: verification must return false.
3. Stop two nodes and verify: false (fail-closed, no quorum).
4. Stop a node and attempt a new write: must not report a confirmed write.
5. For an isolated test DB, tamper with an old block using SQL, then verify a later block: false (full-chain validation).

## Architectural limitations

* This isn't a complete ticket proof: the caller must calculate `recordKey` and `recordHash` from a canonical ticket payload. Arbitrary hashes can be recorded and later matched; there is no signature or binding to a real ticket here.
* Current propose/confirm flows are not atomic. A peer can persist a block before the proposer fails. Pending proposals are in memory; no replay/recovery, leader election, or durable commit log. A 2/3 positive verification result alone isn't proof of a consistent, committed 3-node ledger.
* No trusted signer, TLS/mTLS, audit log or secure key rotation; shared header secret is for demos only.
* Verification of entire chain is O(chain length) per request and must be optimized with validated checkpoints/cache only after correctness is demonstrated.
* Transactions across HTTP and local SQL cannot be made atomic by Spring `@Transactional`.
