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

## 7. 노드 담당 변경안 — 노드 간 통신 제거, FairChain에서 다수결 (10/12)

**팀 합의가 필요한 변경이다.** 설계명세서(SD-0302-03)와 2 · 3번의 node-api 구성과 다르다. 그림과 설계명세서 수정 목록은 `docs/design-change/`.

바로 앞 커밋(`e7a9a18`)은 지금 설계대로 노드끼리 3대 중 2대 합의 + 꺼졌던 노드 자동 따라잡기를 구현한 것이다. 이 변경안을 받지 않으면 그 커밋으로 돌아가면 된다.

### 방식
- FairChain이 **노드 3대에 같은 기록을 각각** 보내고, 2대 이상이 같은 응답(승인 · 블록 번호)을 주면 확정한다
- 노드는 자기 DB만 보고 검증 · 저장한다. **노드끼리는 통신하지 않는다** (서로의 주소도 모름, `NODE_PEERS` 불필요)
- 블록 해시에서 노드 시각을 뺐다 → 같은 순서로 기록을 받은 3대의 블록 번호 · 해시가 같다
- 같은 기록을 다시 받으면 노드는 새 블록 없이 기존 블록 번호를 돌려준다

### 바뀐 파일
| 파일 | 변경 |
|---|---|
| `fairchain/…/BlockRecordRequester.submitRecord` | **FairChain 쪽 유일한 변경.** 노드 1대 → 3대 모두에 보내고 `Majority.pick` |
| `node-api` | `NodeConsensusApi`, `BlockProposalDto` 삭제. `BlockRecordApi` 주석 수정. 나머지 경로 · 헤더 · DTO는 그대로 |
| `node` | `NodeService`를 독립 기록 방식으로 다시 작성. `PendingBlock` 삭제. 주기 점검은 체인 무결성 검사만 |
| `node/…/application.yml` | `node.peers` 삭제, H2 `WRITE_DELAY=0`, `node.audit-interval-ms` |

### 6번 항목
- 6-1 노드 1대가 꺼져도 기록이 멈추지 않는다 (나머지 2대의 응답이 같으면 확정)
- 6-2 체인이 깨진 노드는 새 기록을 받지 않는다 (`accepted: false`). 검증 · 상태 조회 때와 5초마다 체인 전체를 검사한다

### 약점
- 꺼져 있던 노드는 **스스로 따라오지 못한다.** 놓친 기록을 같은 순서로 다시 보내면 같은 체인이 되지만(테스트로 확인), 지금 FairChain에는 그 기능이 없다. 그 전에 새 기록을 받으면 그 노드의 블록 번호가 어긋난 채로 남는다
- 노드 1대가 꺼져 있는 동안 기록마다 그 노드의 응답을 기다린다 (최대 `fairchain.nodes.timeout` 2초)
- 조작된 노드를 자동으로 고치지는 않는다

### 확인한 것
- 테스트: fairchain-api 5 · fairchain 30 (`BlockRecordRequesterTest` 7개 추가) · node 16 — 전부 통과. `NodeClusterTest`는 실제 노드 3대를 띄워 HTTP로 확인
- 실제 프로세스 3개: 기록 5건 → 3대의 블록 번호 · head 해시 일치 / 노드3 종료 중 5건 모두 2대로 확정 / 재시작한 노드3에 놓친 기록을 다시 보내면 체인 일치 / 2대가 꺼지면 확정 안 됨, 돌아온 뒤 같은 기록이 한 번만 확정
- **FairChain + 노드 연결 테스트는 돌리지 못했다** (작업 PC에 MySQL · Redis 없음)
