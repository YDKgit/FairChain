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
