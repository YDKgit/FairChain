# FairChain 서버 + 노드 합치기 정리 (10/12)

`fairchain-server` 브랜치 = **FairChain 서버** + **노드**(`FairChain&NodeServer` 브랜치)
main에는 아직 합치지 않았다. 확인 후 PR로 합칠 예정.

---

## 1. 무엇을 가져오고 무엇을 뺐나

| `FairChain&NodeServer` 브랜치 | 처리 | 이유 |
|---|---|---|
| `node/` 전체 | ✅ 가져옴 | 노드 담당 구현 그대로 사용 |
| `node-api` 변경 (`confirmBlock`에 blockHash, `BlockProofDto`, `NodeLedgerStatusDto`) | ✅ 가져옴 | |
| `fairchain/` (AnchorController, AnchorService, NodeClient …) | ❌ 뺌 | 노드 연동 테스트용 최소 구현으로 보임. FairChain 서버는 `fairchain-server` 구현을 기준으로 함 (fairchain-api 계약 · DBML · 인터셉터 인증 기준) |
| `docs/BLOCKCHAIN-V*.md`, `README-*.md`, `scripts/` | ❌ 뺌 | 작업 단계별 기록 · 윈도우 스크립트 (포트도 옛 값) |

노드 브랜치의 FairChain 코드에서 **아이디어만 옮긴 것** (`ReservationService`)
- 좌석 기록 순서 검사: `ISSUE v1 → PURCHASE → CANCEL ⇄ PURCHASE(재판매) → CHECKIN(끝)`, 버전은 +1씩. 어기면 **409**
  - 단, "이전 기록이 CONFIRMED여야 다음 기록 가능" 조건은 **뺐다** — 블록 기록이 비동기(Redis 대기 줄)라 발행 직후 바로 판매가 와도 받아야 함
- `recordKey` · `recordHash`는 SHA-256 소문자 16진수 64자. 아니면 **400**

아직 안 옮긴 것: PENDING 복구(reconcile), 클러스터 감사(audit) — 노드 쪽 API는 아래 `NodeAuditApi`로 준비해 둠

---

## 2. 노드 쪽 수정 (동작은 그대로)

| 파일 | 변경 |
|---|---|
| `NodeController` | 컨트롤러에 직접 만든 경로(proof · ledger status · decision · recovery)를 node-api 인터페이스로 옮김 → **경로 애너테이션 없이 implements만** |
| `NodeService` | 노드 ↔ 노드 호출 경로 문자열 → `NodeConsensusApi.PROPOSALS` · `decisionPath()` · `confirmPath()` 상수 |
| `NodeAuthFilter` | `"X-Node-Secret"` 문자열 → `NodeHeaders.NODE_SECRET` |
| `node/src/main/resources/application.yml` | 기본 포트 **8081~8083 → 9001~9003** (8081은 FairChain 포트라 겹침) |

---

## 3. node-api 변경 (공용 계약)

| 인터페이스 | 경로 | 누가 호출 |
|---|---|---|
| `BlockRecordApi` (기존) | `POST /node/records` · `POST /node/records/verify` | FairChain |
| `NodeVerificationApi` (기존) | `POST /node/records/verify-batch` | FairChain |
| **`NodeAuditApi` (신규)** | `GET /node/records/proof/{recordKey}` · `GET /node/ledger/status` | FairChain (PENDING 복구 · 감사) |
| `NodeConsensusApi` | `POST /node/consensus/proposals` · `…/blocks/{i}/confirm?blockHash=` | 노드 ↔ 노드 |
| | **추가** `POST /node/consensus/blocks/{i}/decision?blockHash=` | 노드 ↔ 노드 |
| | **추가** `POST /node/consensus/recovery/{i}?blockHash=` | 운영자 수동 |
| **`NodeHeaders` (신규)** | `NODE_SECRET = "X-Node-Secret"` | `/node/**` 모든 요청 |

FairChain → 노드 인증도 **`X-Node-Secret`** 으로 통일했다.
(`fairchain-api`의 `RestHttpRequester`에 헤더 이름을 받는 생성자를 추가. 기존 생성자는 그대로라 예제서버 영향 없음)

---

## 4. 실행 방법

JDK 17 필요. `./gradlew build`는 `example-server`에 아직 코드가 없어서 실패하니 **모듈을 지정**해서 실행한다.

```bash
./gradlew :node:test :fairchain:test          # 테스트
./gradlew :node:bootJar :fairchain:bootJar    # 실행 파일
```

### 노드 3대 (설정 없이 바로 실행, H2 파일 DB)
```bash
NODE_PORT=9001 NODE_DB=./data/node1 NODE_PEERS=http://localhost:9002,http://localhost:9003 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
NODE_PORT=9002 NODE_DB=./data/node2 NODE_PEERS=http://localhost:9001,http://localhost:9003 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
NODE_PORT=9003 NODE_DB=./data/node3 NODE_PEERS=http://localhost:9001,http://localhost:9002 java -jar node/build/libs/node-0.0.1-SNAPSHOT.jar
```
노드 공유 비밀값 기본값: `local-demo-change-me` (`NODE_SECRET` 환경변수로 변경)

### FairChain (포트 8081)
필요: **MySQL**(`fairchain` DB · 계정) + **Redis**(6379)

1. `fairchain/src/main/resources/application-example.yml`을 복사해 `application-local.yml` 생성 (gitignore — GitHub에 안 올라감)
2. DB 비밀번호, 고객사 API Key, 운영자 계정, **`fairchain.nodes.api-key`(= 노드 비밀값과 같은 값)** 입력
3. 실행
```bash
java -jar fairchain/build/libs/fairchain-0.0.1-SNAPSHOT.jar
```
- Swagger: http://localhost:8081/swagger-ui.html (Authorize → `X-API-KEY`)
- 대시보드: http://localhost:8081/dashboard.html

---

## 5. 연결 테스트 결과 (FairChain + 노드 3대)

| 시나리오 | 결과 |
|---|---|
| 좌석 2개 발행 → 바로 판매 | 응답 즉시 200 (PENDING) → **3초 안에 CONFIRMED**, 노드 3대 head 해시 일치 |
| 체크인 검증 (정상 / 지문 조작) | `VERIFIED` / `TAMPERED` |
| 목록 검증 | `nodeMatched: true` |
| 노드2 블록을 DB에서 조작 | 노드2만 `chainValid: false`, FairChain은 **2/3 다수결로 정상 티켓 VERIFIED** |
| 노드3 종료 후 기록 | ❌ PENDING에서 멈춤, **노드3 재시작 후에도 안 풀림** — 이후 기록 전부 대기 |

테스트 수: fairchain-api 5 · fairchain 23 · node 18 — 전부 통과

---

## 6. 같이 정해야 할 것

1. **노드 1대만 꺼져도 블록 기록이 영구히 멈춤**
   3/3 합의라 준비 블록이 남으면 노드가 새 기록을 전부 거절하고, 확정 결정이 없는 준비 블록은 복구 API로도 못 푼다.
   FairChain은 비동기라 사용자에게는 정상 응답이 가서 겉으로 안 보인다. → **2/3 합의로 변경** 또는 막힌 블록 정리 방법 필요 (발표에서도 PBFT 2/3로 설명해 왔음)
2. **체인이 깨진 노드도 새 블록 제안에 찬성함** → 깨진 노드는 투표에서 빠져야 함
3. `ticket_heads`(티켓 단위 잠금 테이블) 추가 여부 — DBML에 없어서 팀 합의 필요. 현재는 `anchor_record`의 `UNIQUE(record_key, version)`로 같은 버전 중복만 막음
4. 앞으로 노드 작업은 이 브랜치 기준으로 (노드 브랜치의 FairChain 코드와 다시 충돌하지 않게)

---

## 7. 노드 수정 — 6-1 · 6-2 해결 (10/12, 노드 담당)

FairChain 쪽 코드는 바꾸지 않았다. 노드 주소 · 포트 · `X-Node-Secret` · 기존 node-api 경로는 모두 그대로.

| 문제 | 수정 |
|---|---|
| 6-1 노드 1대만 꺼져도 기록이 멈춤 | **3대 중 2대가 찬성하면 확정.** 확정 결정 · 확정은 찬성한 노드에만 보낸다 |
| | 끝나지 못한 준비 블록은 다음 기록 요청이나 주기 점검 때 **같은 블록으로 다시 돌려 마무리**한다 (더 이상 새 기록을 막지 않음) |
| | 꺼져 있던 노드는 켜진 뒤 다른 노드의 확정 블록을 받아 **스스로 따라잡는다** — `GET /node/consensus/blocks?from=` (`NodeConsensusApi`에 추가) |
| 6-2 체인이 깨진 노드도 찬성함 | 깨진 것이 확인된 노드는 **찬성 · 제안 · 블록 제공을 하지 않는다.** 주기 점검(기본 5초)과 검증 · 상태 조회 때 체인 전체를 검사한다 |

같이 바뀐 것
- 노드 ↔ 노드 호출 제한 0.8초 (`NODE_PEER_TIMEOUT_MS`) — FairChain의 노드 호출 제한 2초 안에 합의가 끝나도록
- 주기 점검 5초 (`NODE_SYNC_INTERVAL_MS`) — 체인 검사 → 따라잡기 → 남은 준비 블록 마무리
- H2 `WRITE_DELAY=0` — 강제 종료돼도 확정 블록 · 표가 남도록
- 새 블록의 timestamp는 초 단위 — DB 시각 정밀도와 무관하게 해시가 같도록
- `recovery`: 확정 결정이 없는 준비 블록도 다시 돌릴 수 있다

안전 규칙: 각 노드는 블록 번호 하나에 제안 하나에만 찬성하고 그 표를 바꾸지 않는다. 그래서 같은 번호에 서로 다른 블록이 확정될 수 없다.

남은 제약
- 서로 다른 두 노드에 **동시에** 다른 기록을 제출했는데 나머지 1대가 꺼져 있으면, 그 번호는 꺼진 노드가 돌아올 때까지 확정되지 않는다. FairChain은 대기 줄을 하나씩 순서대로 보내므로 보통은 생기지 않는다
- 조작된 노드를 자동으로 고치지는 않는다. `chainValid: false`로 드러내고 투표에서 뺄 뿐이다
- 조작이 다른 블록에서 일어난 경우 그 노드가 투표를 멈추기까지 최대 주기 점검 간격(5초)이 걸린다

확인한 것
- 테스트: fairchain-api 5 · fairchain 23 · node 30 — 전부 통과. `NodeClusterTest`는 실제 노드 3대를 띄워 HTTP로 확인한다
- 실제 프로세스 3개: 노드3 종료 후 기록 5건 모두 확정(최대 0.12초) → 노드3 재시작 후 스스로 따라잡음 / 노드1 종료 시 노드2가 제안해 확정 / 2대가 꺼지면 확정하지 않고, 돌아온 뒤 같은 기록이 한 번만 확정
- **FairChain + 노드 연결 테스트는 다시 돌리지 못했다** (작업 PC에 MySQL · Redis 없음) — 5번 표의 마지막 시나리오를 다시 확인해 주세요

---

## 8. 독립 노드 변경안(`7e3e859`) 되돌림 — 노드끼리 합의로 복귀 (10/24, `node-consensus` 브랜치)

블록체인이라고 설명하려면 노드끼리 합의하는 쪽이 맞고, 꺼졌던 노드의 따라잡기가 필요해서 `7e3e859`를 되돌렸다. 7번 내용(3대 중 2대 찬성, 스스로 따라잡기, 깨진 노드 투표 중단)이 다시 기준이다. `docs/design-change/`는 같이 삭제됐다. 설계명세서(SD-0302-03)는 고치지 않아도 된다.

10/15 이후 변경과 합친 것
- 점검 화면용 블록 목록(10/15)과 따라잡기용 블록 조회(`e7a9a18`)의 메서드 이름이 둘 다 `blocks`라 겹쳤다. 되살린 합의 코드는 그대로 두고, 점검 화면용을 `NodeAuditApi.ledgerBlocks`로 바꿨다 (경로 `/node/ledger/blocks`는 그대로라 점검 화면은 영향 없음)
- `scripts/run-local.ps1`: 노드마다 `NODE_PEERS`(나머지 두 노드 주소)를 넘긴다. IntelliJ로 띄울 때도 노드 2 · 3에는 꼭 넣어야 한다 (기본값은 노드 1 기준)
- 블록 해시에 시각이 다시 들어간다 → 독립 노드 버전으로 만든 노드 DB와 섞어 쓰지 말고 블록을 초기화하고 시작한다

확인한 것
- 테스트: fairchain-api 5 · fairchain 23 · node 31 — 전부 통과
- 노드 3대만: 노드3 끄고 기록 4건 → 켜자 스스로 따라잡음 / 노드3 DB의 2번 블록을 고침 → `chainValid: false`, 기록 제출 거절, 검증 false, 나머지 2대로 계속 확정 / 노드3 DB 삭제 후 켬 → 1번부터 다시 받아 일치
- FairChain + 노드 3대 (H2 · 시연용 Redis): 좌석 발행 · 예매 → VERIFIED / FairChain이 먼저 보내는 노드1을 끄고 예매 → 노드2로 넘어가 확정, VERIFIED / 노드1 다시 켬 → 따라잡음, 이후 기록도 3대 일치

### 8-1. 블록 해시에서 제안 시각을 뺐다 (10/24)

증상: 노드를 하나씩 껐다 켜며 기록하면, 세 노드가 같은 번호를 "준비 중"으로 들고 영원히 확정하지 못했다 (`pendingIndices`가 3대 모두 같은 번호, FairChain 블록 대기 줄이 멈춤).

원인: 블록 해시에 제안 시각이 들어가서, **같은 기록이라도 노드마다 다른 시각에 제안하면 다른 블록**이 됐다. 노드는 번호 하나에 표 하나만 주므로 각자 자기 제안에만 찬성 → 과반이 안 나옴. 노드1만 켜진 상태 → 노드2만 → 노드3만 순서로 같은 기록을 보내면 그대로 재현된다.

수정 (`NodeService`):
- `hash()`에서 시각을 뺐다. 시각은 블록에 기록만 한다 (독립 노드 변경안에서 뺐던 것과 같은 이유)
- 받은 제안이 내 표와 같은지는 블록 해시로 비교한다 (`propose`), 확정 블록 비교(`sameBlock`)에서도 시각을 뺐다
- 테스트 `sameRecordProposedAtDifferentTimesIsTheSameBlock` 추가. 위 재현 순서로 실제 노드 3대를 돌려 확인: 셋 다 켜자 바로 확정, 이후 기록도 3대 일치

블록 해시 형식이 바뀌었으므로 기존 노드 DB와 섞지 말고 블록을 초기화하고 시작한다.

### 8-2. 확정 기준을 명단 기준 과반으로 (10/24)

`QUORUM = 2`(3대 전용)와 "peers는 정확히 2개" 검사를 없애고, 확정에 필요한 찬성 수를 **참여 노드 명단(나 + NODE_PEERS) 전체의 과반**으로 계산한다. 3대 → 2표(지금과 같음), 5대 → 3표, 10대 → 6표. 켜져 있는 노드 수가 아니라 명단 기준이라, 꺼진 노드가 있어도 기준은 낮아지지 않는다. 테스트 `quorumIsAMajorityOfTheWholeMemberList` 추가 (node 33개 통과).

측정용 `scripts/bench-nodes.sh` (맥 · zsh): 노드 N대를 9101부터 띄우고(`data/bench`, 시연용 노드와 별개) 기록을 하나씩 보내 확정 시간을 잰다. `start N` · `run 건수` · `kill 번호` · `status` · `stop`.

---

## 9. 비교용 브랜치 `fairchain-vote` — FairChain에서 다수결 (10/26, 노드 담당)

`demo-page`(노드끼리 투표)와 처리 속도를 비교하려고 만든 브랜치다. `demo-page`를 기준으로 노드 부분만 독립 노드 방식(`7e3e859`)으로 바꿨다. 시연 페이지 · 점검 화면 · 예제서버 · FairChain의 나머지 코드는 같다.

바뀐 것
- `node` · `node-api`: 노드끼리 통신하지 않는다. 각 노드는 자기 DB만 보고 블록을 추가해 결과를 돌려준다. `NodeConsensusApi` · `BlockProposalDto` · `PendingBlock` 없음. 점검 화면용 메서드 이름 `ledgerBlocks`는 그대로
- `fairchain` `BlockRecordRequester.submitRecord`: 노드 3대에 **동시에** 보내고(노드마다 스레드 1개) 2대 이상 같은 결과면 확정한다. 3대의 응답(또는 제한 시간 2초)을 받은 뒤에 다음 기록을 보내므로 노드마다 기록 순서가 같다. 검증(`verifyRecord`)은 `demo-page`와 같다
- `scripts/run-local.ps1`: `NODE_PEERS` 없음
- `scripts/bench_confirm.py` 추가: 지금 브랜치의 jar로 노드 3대 + FairChain을 띄우고, FairChain을 거쳐 기록 N건이 모두 CONFIRMED가 될 때까지 시간을 잰다. 두 브랜치에서 빌드 후 같은 명령으로 돌린다 (`demo-page`에서는 이 파일을 복사해서 실행)

측정 (같은 PC, 기록 200건, 2회 평균, `python scripts/bench_confirm.py --records 200`)

| | `demo-page` (노드끼리 투표) | `fairchain-vote` (FairChain 다수결) |
|---|---|---|
| 좌석 발행 200건 → 모두 확정 | 31.7초 (1건당 159 ms) | 8.0초 (1건당 40 ms) |
| 예매 200건 → 모두 확정 | 23.9초 (1건당 120 ms) | 5.4초 (1건당 27 ms) |

두 브랜치 모두 끝난 뒤 노드 3대의 마지막 블록 해시가 같았다. 한 PC에서 잰 값이라 절대값보다 차이(약 4배)를 보면 된다.

남은 제약 (이 브랜치)
- 꺼졌던 노드는 스스로 따라잡지 못한다. 놓친 기록을 FairChain이 기록 사본(`anchor_record`)에서 순서대로 다시 보내 주는 기능이 필요하다 (아직 없음). 그래서 시연 페이지의 "노드 끄고 다시 켜기" 장면은 이 브랜치에서 맞지 않는다
- `scripts/bench-nodes.sh` · `demo-nodes.sh`는 노드끼리 투표하는 방식 기준이다. 이 브랜치에서는 `bench_confirm.py`를 쓴다
- 설계명세서(SD-0302-03)는 노드 간 합의 방식이다. 이 방식으로 정하면 설계명세서를 고쳐야 한다
