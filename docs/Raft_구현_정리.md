# FairChain 노드 — Raft 구조 구현 정리

작성: 이고르 (노드 담당) · 2026-11-17 · 브랜치 `raft-node` (`demo-page`에서 갈라짐)
함께 보는 문서: 「노드 구조 변경안」(11/06), 「미정 사항 답변」(11/12, 윤도균), `docs/시연_시나리오.md`, `docs/측정_기준.md`

11/12 답변에서 "꼭 넣을 것"으로 정한 범위를 모두 구현했습니다. 답변대로 하지 못했거나 답변에 없는 곳은 3장에 이유와 함께
적었습니다. 표준(Raft · MicroRaft · Hyperledger Fabric)과 다르게 한 곳은 없고, 표준 안에서 고른 곳만 있습니다.

---

## 1. 구조

```
FairChain ──기록──► 아무 리더 층 노드 (팔로워면 Raft 리더에게 넘김)
                     [리더 층 = Raft 그룹 1개]  A1 ─ B1 ─ C1   (팀마다 1대, MicroRaft 0.7)
                     리더가 블록을 만들어 로그로 복제 → 과반 저장 = 확정 (왕복 1번)
                     노드마다 확정 블록을 자기 H2에 쓰면서 자기 키로 서명 (Fabric 방식)
                              │ 확정 블록 (팀 리더의 사본 · 서명)
                              ▼
                     [팀원 = 투표하지 않는 사본]  A2 · A3 / B2 · B3 / C2 · C3
                     서명 · prevBlockHash · 해시 확인 → 저장 → 팀 리더에게 "저장함" 또는 이유 코드
```

## 2. 11/12 답변 항목별로 무엇을 만들었나

| # | 답변 | 구현 |
|---|---|---|
| 1 | 멤버는 노드(주소 + 공개키), 팀 ID는 속성 | `MemberRegistry` · Raft 로그의 `RegisterMember`. 설정 `node.members`는 처음 그룹만 |
| 2 | 팀원은 투표하지 않는 사본, 서명 · prevBlockHash 확인, 팀 리더에게 응답 | `MemberReplicaService` (`node.role=member`). 응답 `MemberAckDto{저장함 · 이유 코드 · 마지막 번호 · 해시}` |
| 3 | 리더가 블록 생성, 새 리더는 임기 첫 항목 확정 뒤부터 | `NodeService.submitAsLeader` + MicroRaft LINEARIZABLE 조회로 임기 첫 항목 확정 확인 |
| 4 | 1건 = 1블록 | 그대로 (블록 형식 · 검증 API 변화 없음) |
| 5 | FairChain은 아무 노드에나, 팔로워가 리더에게 전달 | 팔로워가 한 번 전달 (`X-Node-Forwarded`) |
| 6 | 같은 기록(recordKey + version)은 기존 블록, 실패하면 대기 줄 맨 앞, 재전송 3번 | 노드: 중복이면 기존 번호. FairChain: `BlockRecordService` 3번 → 맨 앞 |
| 7 | 하트비트 1초 · 시간 제한 4초, 5초 안에 새 리더 | 설정값 그대로. 측정은 5장 |
| 8 | 확정 블록의 blockHash에 서명, 블록 옆 칸에 저장, 계산식 그대로 | Ed25519, `Block.signerId · signature` 칸. 서명하는 쪽은 3장 ① |
| 9 | 노드마다 키, 개인키는 H2 밖 파일, 공개키 명단은 Raft 로그 | `NodeKeys` (`node.key-file`, 기본 `data/nodeN.key`). 키 교체 = 해지 후 재가입 |
| 10 | 제외 · 복귀는 리더 층 Raft 로그에, 팀원 제외도 같은 곳 | `Exclude` · `Restore` 로그 항목. 제외된 노드는 Raft를 멈춤 (3장 ③) |
| 11 | #k부터 다시 받기, Raft 상태는 그대로, #1부터 다르면 관리자 | `RecoveryService` · 팀원 복구. #1부터 다른 리더 층 노드는 자동 복구하지 않음 |
| 12 | 켤 때 · 투표 · 조회 때 검사 + 순회 검사(옵션, 기본 끔) | 켤 때 해시 + 서명, 조회 때 해시, `PatrolService` (`node.patrol.enabled`) |
| 13 | 조회 응답에 마지막 블록 번호, 번호가 작으면 뒤처짐 | `lastBlockIndex`. FairChain이 뒤처짐은 신고하지 않음 |
| 14 | 신고는 리더가 받음, 뒤처짐/갈라짐 → 이진 탐색 → 서명 검증 → 로그 | `DivergenceService`. 증거 = #k의 두 버전 · 달라진 필드 · 시각 |
| 15 | 노드 관리 API로 가입 · 해지, learner → 승격, 3팀 미만 거부, 한 번에 하나 | `POST /node/admin/members` · `DELETE /node/admin/members/{id}`. FairChain 화면은 조회만 |
| 16 | 블록 체인이 스냅샷, 로그는 주기적으로 자름 | 1000건마다 스냅샷(명단 · 키 · 제외 · 판정만), 빠진 블록은 `GET /node/consensus/blocks` |
| — | 키가 둘 다 맞는데 내용이 다르면 기록 중단 + 관리자 (11/06 6장) | `Halt` → 기록 거부 → 관리자 `POST /node/admin/resume` |

## 3. 답변과 다르게 하거나 답변에 없어서 정한 곳

① **서명은 Fabric 방식** (답변 8번은 "확정한 리더가 서명해 다음 메시지로 보냄").
리더가 확정 뒤 다음 메시지로 서명을 보내면, 그 사이에 리더가 꺼질 때 서명 없는 블록이 남고 그 규칙이 없습니다. 또 MicroRaft의
하트비트에 우리 데이터를 넣으려면 라이브러리를 고쳐야 합니다. Fabric은 orderer마다 확정 블록을 자기 장부에 쓸 때 서명합니다
(`blockwriter.go`). 그래서 리더 층 노드마다 자기 사본에 서명하고, 팀원은 블록을 보낸 리더 층 노드의 서명을 확인합니다.
"확정된 블록에만 서명", "노드마다 키"는 그대로입니다.

② **블록 검사는 "받기 전"이 아니라 "적용할 때"** (답변의 "StateMachine이나 Transport 쪽에 넣을 수 있는지 확인").
Raft 팔로워는 로그 항목을 내용 때문에 거부할 수 없습니다 (거부하면 로그 일치 규칙이 깨짐). 그래서 확정된 항목을 적용할 때
번호 · prevBlockHash · 해시 · 버전을 검사하고, 통과한 블록만 H2에 들어갑니다. 내 H2가 로그와 다르면 그 노드는 스스로 빠지고
자신을 신고합니다.

③ **제외 = 그 노드가 Raft를 멈춤, 명단에는 남음**. 메시지만 무시하면 선거 타이머가 계속 돌아 다른 노드를 방해합니다.
멈추면 "투표 · 후보 · 블록 제공 안 함"이 그대로 됩니다. 복구하면 Raft 상태 파일로 다시 시작합니다.

④ **공개키 등록**: 처음 그룹의 노드는 켜진 뒤 스스로 리더에게 공개키 등록을 요청합니다 (`POST /node/consensus/keys`,
Fabric genesis 블록의 orderer 인증서에 해당). 키를 설정 파일에 손으로 옮기지 않아도 됩니다. 이미 멤버인 노드만, 처음 한 번만.

⑤ **이유 코드 2개 추가**: `NOT_LEADER`(리더를 모름 → 다른 노드로), `TIMEOUT`(시간 안에 과반 저장 안 됨 → 같은 기록 다시 보내기).
노드끼리의 거부가 아니라 FairChain에 돌려주는 결과라서 11/06 목록에 더했습니다.

⑥ **Raft 상태 저장소**: 11/12 범위표에 없지만 없으면 재시작한 노드가 임기 · 투표를 잊습니다. MicroRaft의 SQLite 저장소를
쓰는데, 0.7에는 "투표한 상대가 없으면 저장된 상태가 없다고 보는" 버그가 있어 감싸서 씁니다 (라이브러리는 고치지 않음).

⑦ **선거 시간 제한을 노드마다 0~500ms 다르게**: MicroRaft는 선거마다 0~100ms만 더해서, 한 PC에 프로세스 9개를 띄우면 후보
둘이 같은 순간에 계속 출마해 표가 갈리는 일이 있었습니다 (Raft 논문 5.2절의 "무작위 선거 시간"). 설정값만 쓰고 라이브러리는
고치지 않았습니다.

⑦′ **리더 장애 감지 보완 (MicroRaft 0.7의 빈틈)**: 팔로워가 사전 투표를 시작했다가 거절당한 뒤 같은 임기의 리더를 알게 되면
MicroRaft는 그 사전 투표 상태를 지우지 않고, 그동안 하트비트 검사를 건너뜁니다. 선거 직후나 재시작 직후에 생기고, 그 노드는
리더가 꺼져도 스스로 선거를 시작하지 않습니다 (로그로 확인: 살아남은 두 노드 중 한쪽만 "heartbeats are timed-out"을 남김).
그래서 MicroRaft가 보고하는 "리더 하트비트를 마지막으로 받은 시각"이 시간 제한을 넘었는데도 팔로워이면, 리더십 이전 때 쓰는
MicroRaft의 메시지(`TriggerLeaderElectionRequest`)를 그 노드에 넣어 표준 선거를 시작합니다. 표준 Raft라면 이미 선거를
시작했을 때만 하므로 동작은 Raft 그대로이고, 라이브러리는 고치지 않았습니다 (`RaftNodeHolder.checkLeaderSilence`).
마지막 측정 8회 중 7회는 MicroRaft가 스스로 선거를 시작했고, 1회는 이 보완이 시작했습니다.

⑧ **팀원에게 블록 전달은 보내기(push) + 가져가기(pull)**: 팀 리더가 블록을 쓰면 바로 보내고(지연 작음), 팀원은 1초마다 빠진
블록을 가져갑니다(놓친 것 · 팀 리더가 꺼진 동안). 팀원 등록은 팀원이 1초마다 팀 리더에 알리는 방식이라 팀 리더 명단은
Raft 로그에 넣지 않습니다 (답변 1 · 2번).

⑨ **팀원은 장부 전체(#1부터)가 달라도 자동 복구**: 팀원은 Raft 멤버가 아니라 비우고 다시 받으면 끝입니다. 리더 층 노드는
답변 11번대로 관리자가 해지 → 비우기 → learner로 재가입합니다.

⑩ **FairChain 설정은 그대로**: 노드 ID는 설정에 적지 않고 노드 상태 조회에서 받습니다. 주소 목록(`fairchain.nodes.urls`)만 있으면 됩니다.

## 4. 실행

```
gradlew :node:bootJar :fairchain:bootJar
python scripts/raft_nodes.py start --fresh            # PC 1대: 리더 층 3대 + 팀원 6대
python scripts/raft_nodes.py status
python scripts/raft_nodes.py scenario                 # 시연 순서 전체 자동 확인
```

PC 3대(PC 1대 = 팀 1개)와 시연 순서는 `docs/시연_시나리오.md`. 노드 설정은 `node/src/main/resources/application.yml`
(`NODE_ID` · `NODE_MEMBERS` · `NODE_ROLE` · `NODE_URL` · `NODE_KEY_FILE` …). 예전 장부(서명 없는 블록)로는 켤 때 검사에서
빠지므로 빈 장부에서 시작합니다 (답변 8번: "시연은 매번 빈 장부에서 시작").

## 5. 측정 (PC 1대, `docs/측정_기준.md`)

| | 지금 방식 (`demo-page`) | Raft (`raft-node`) |
|---|---|---|
| 노드만, 300건 (FairChain처럼 노드1에 보냄) | 9.5 건/s · 105 ms | 31.0 건/s · 33 ms (서명 포함) |
| FairChain을 거쳐 좌석 200석 발행 / 예매 200건 | 24.2초 / 21.9초 | 11.9초 / 9.9초 |
| 리더 장애 → 새 리더 (프로세스 9개, 8회) | (리더 없음) | 평균 4.36초 · 최대 4.55초, 다음 기록 확정 최대 4.63초 (하트비트 1초 · 시간 제한 4초) |

PC 3대 측정은 아직 못 했습니다 (9단계, 같은 명령으로 `측정_기준.md`에 채움).

## 6. FairChain 쪽에서 바뀐 곳 (윤도균 확인 부탁)

요청 경로(requester)와 화면 조회만 바꿨습니다. 컨트롤러 · 대기열 구조 · 예매 흐름은 그대로입니다.

| 파일 | 바뀐 것 |
|---|---|
| `block/requester/BlockRecordRequester` | 검증 다수결을 답의 내용(일치 · 버전 · 지문)만으로 — 노드마다 마지막 번호가 잠깐 달라도 다수결이 나오게 (이걸 안 고치면 기록 중에 정상 티켓이 "다수 응답 없음"이 될 수 있음). 소수 노드가 뒤처짐이 아니면 신고 |
| `block/requester/NodeVerificationRequester` | 묶음 검증에서 그 블록을 가진 소수 노드만 신고 |
| `block/requester/NodeDivergenceReporter` (새 파일) | 신고만 보냄 (따로 도는 스레드, 같은 노드는 10초에 한 번). 노드 ID는 노드 상태 조회에서 |
| `block/service/BlockRecordService` | 한 기록을 3번까지 보내고 그래도 안 되면 맨 앞으로 (11/06 6장) |
| `demo/DemoController`, `static/demo.html` | 노드 카드에 역할 · 임기 · 빠진 이유 · 팀원, 요약에 판정 기록 (조회만) |
| `inspect/InspectorService`, `static/inspector.html` | 노드별 Raft 상태 · 판정 기록 (조회만) |
| 테스트 | `NodeReportingTest`(4), `BlockRecordRetryTest`(2) 추가. 기존 `FairChainApiTest` 19개 그대로 통과 |

## 7. 설계명세서에서 고칠 곳

- 노드 합의 시퀀스(SD-0302-03 등): 제안 → 결정 → 확정 3단계 → "FairChain → 노드 → (팔로워면 리더에게 전달) → 리더가 블록 생성 ·
  Raft 로그 복제 → 과반 저장 → 확정 → 노드마다 서명 · 저장 → 팀원 전달" 로
- 클래스 다이어그램(노드): `NodeService` 아래에 `RaftNodeHolder` · `BlockStateMachine` · `HttpRaftTransport` · `Ledger` ·
  `NodeKeys` · `DivergenceService` · `RecoveryService` · `MembershipService` · `TeamLeaderService` · `MemberReplicaService`
- 판정 시퀀스 새로: 신고 → 뒤처짐/갈라짐 → 이진 탐색 → 서명 검증 → 제외(로그) → #k부터 복구 → 복귀(로그)
- node-api 계약: 새 API (`NodeRaftApi` · `NodeReportApi` · `NodeAdminApi` · `NodeMemberApi` · `NodeTeamApi`), 사라진 API
  (`proposals` · `decision` · `confirm` · `recovery`)

## 8. 남은 일

- PC 3대 측정 (지금 방식 · Raft 같은 조건) → 하트비트 시간 제한 값 확정 (미정사항 7번)
- 팀 회의: 위 3장 ① ② ③을 그대로 갈지, 담당 · 일정 (11/12 답변 끝)
- "시간이 되면" 항목: 블록에 기록 여러 건, 팀 리더 자동 교체, 노드 간 메시지 서명 · 기관 과반 승인 — 손대지 않음
