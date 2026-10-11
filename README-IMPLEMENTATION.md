# FairChain / three-node MVP

The new applications use Spring Boot 3.3.5, Spring Data JPA and H2 file databases for a **local demonstration**. The original API modules remain in place.

## Start

Java 17+ needed. From the project root:

```bash
./gradlew :node:bootJar :fairchain:bootJar
NODE_PORT=8081 NODE_DB=./data/node1 NODE_PEERS=http://localhost:8082,http://localhost:8083 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
NODE_PORT=8082 NODE_DB=./data/node2 NODE_PEERS=http://localhost:8081,http://localhost:8083 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
NODE_PORT=8083 NODE_DB=./data/node3 NODE_PEERS=http://localhost:8081,http://localhost:8082 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
DEMO_API_KEY=local-demo-key java -jar fairchain/build/libs/fairchain-0.0.1-SNAPSHOT.jar
```
Run each in a separate terminal. For node testing, use the node endpoints with `X-Node-Secret: local-demo-change-me`. The FairChain endpoint `/api/anchors` requires a Clients row with `apiKey` in the FairChain database (optionally seeded with `DEMO_API_KEY=local-demo-key`). The record key and record hash must be 64-character lowercase SHA-256 hex strings.

## Important limitations

* This is a functional **MVP architecture**, NOT PBFT. A single proposer gathers votes, then issues confirm messages; network failures can cause divergent states, and the confirmation protocol is not atomic. We deliberately require three confirmations for the normal success path.
* No recovery, leader election, durable pending proposals, authentication beyond shared-secret demo header, or production-ready authorization, migrations, and HTTPS. Do not use as a trusted blockchain for real tickets.
* Only AnchorRecord flow is implemented; Event/Admission/AdmissionPass/OperatorAccount/Clients have JPA models but their business processes are not implemented.
* JPA `ddl-auto: update` and H2 should be replaced by MySQL migrations for deployment.

## Smoke test (after starting all four processes)

```bash
curl -X POST http://localhost:8080/api/anchors \
 -H 'Content-Type: application/json' -H 'X-API-Key: local-demo-key' \
 -d '{"type":"ISSUE","eventId":1,"userId":null,"slotKey":"A1","recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'

curl -X POST http://localhost:8080/api/anchors/verify \
 -H 'Content-Type: application/json' -H 'X-API-Key: local-demo-key' \
 -d '{"recordKey":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","version":1,"recordHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
```
Expect the first response to have `blockState: CONFIRMED`, then `true` from verification. The values above are only synthetic test digests, not proofs tied to a ticket.
