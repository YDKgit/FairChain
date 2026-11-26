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
| 15 | 노드 관리 API로 가입 · 해지, learner → 승격, 3팀 미만 거부, 한 번에 하나 | `POST /node/admin/members` · `DELETE /node/admin/members/{id}`. 가입은 주소만으로 (가입 전 확인). **"FairChain 화면은 조회만"은 바꿈** — 점검 화면에서 FairChain 운영자가 추가 · 해지 (3장 ⑪ ⑫) |
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
이 목록은 **출발점**입니다. FairChain은 3초마다(`fairchain.nodes.refresh-ms`) 노드 상태를 묻고 리더가 알려 주는 멤버 명단(Raft 로그로
정해진 값)의 투표 멤버에 맞춥니다 — 노드가 추가 · 해지돼도 FairChain을 다시 켜지 않습니다 (etcd 클라이언트가 멤버 목록으로 접속
주소를 맞추는 것과 같음, `NodeDirectory`). 조회 다수결의 기준도 그 목록 크기를 따릅니다. 기준은 2026-11-23 팀 결정으로 2/3에서 **과반**으로 바꿨습니다(기록과 같은 기준): 3대 → 2대, 5대 → 3대, 9대 → 5대.

⑪ **노드 추가 · 해지를 FairChain 점검 화면에서** (답변 15번 "FairChain 화면에서는 호출하지 않는다"를 바꿈 — 교수님 질문
"노드를 자유롭게 늘리고 줄일 수 있게 돼 있나"와 그 뒤 팀 논의).
답변 15번의 이유는 "FairChain 혼자서 기관을 뺄 수 없어야 한다"였는데, 블록체인을 FairChain이 운영하는 것으로 정했으므로 추가 · 해지도
FairChain 운영자가 합니다. 화면: `/inspector.html` → 블록체인 노드 → **노드 추가**(주소 입력) · 노드 카드의 **해지**.
- FairChain은 요청을 노드에 전달만 합니다. learner → 따라잡기 → 투표 멤버 승격, 3팀 미만 해지 거부, 한 번에 하나는 그대로 리더가
  표준 Raft 멤버 변경으로 합니다 (바뀐 것은 "누가 버튼을 누르나"뿐).
- 누를 수 있는 사람: 운영자 계정이 고객사마다 있어서 대시보드 토큰만으로는 고객사 운영자도 누를 수 있게 됩니다. 그래서 설정에 적은
  운영자만 됩니다 — `fairchain.nodes.managers`(로그인 ID 목록), 비우면 seed 운영자(`fairchain.seed.operator-login-id`). 그 밖의
  운영자에게는 버튼이 안 보이고 API는 403입니다.
- 리더 층은 팀마다 노드 1대입니다. 이미 있는 팀의 둘째 노드와 팀원 노드(`node.role=member`)는 리더 층 가입을 거부합니다
  (팀원은 지금처럼 켜지면 자기 팀 리더에 스스로 붙음).

⑫ **가입 전 확인 — 가짜 노드 막기** (답변에 없던 것). 화면에 IP · 포트만 적어 노드를 붙이면 가짜 노드도 붙일 수 있다는 걱정에 대한 답입니다.
운영자가 주소를 주면 리더가 그 주소에 무작위 값(nonce)을 보내고(`POST /node/join/proof`), 새 노드는 두 가지를 돌려줍니다.
- 노드 공유 비밀값으로 만든 HMAC-SHA256 → **비밀값을 아는 노드 = FairChain이 띄운 노드**
- 자기 개인키로 만든 Ed25519 서명 → **명단에 등록될 공개키의 주인**
리더는 둘 다 맞고, 노드가 알고 있는 자기 주소(`NODE_URL`)가 입력한 주소와 같을 때만 받습니다 (가짜 주소가 진짜 노드의 답을 받아다
전달하는 것을 막음). ID · 팀 · 공개키는 운영자가 적지 않고 이 답에서 받습니다.
- 예전 구현의 문제도 같이 고쳤습니다: 공개키를 받으려고 **확인되지 않은 주소에 비밀값 헤더를 붙여 상태 조회를 보냈습니다** — 가짜
  주소를 넣으면 비밀값이 그쪽으로 나갔습니다. 지금은 확인이 끝나기 전에는 그 주소로 비밀값이 나가지 않습니다 (테스트로 확인).
- 표준과의 관계: 주소로 멤버를 추가하는 것은 etcd `member add`, 가입할 쪽이 공유 비밀을 증명하는 것은 kubeadm · Docker Swarm의 가입
  토큰과 같은 자리입니다. 운영 환경의 표준은 인증서(mTLS)인데, 인증서 발급 체계가 없어 이미 있는 공유 비밀값으로 확인합니다.
  그래서 **비밀값은 길고 무작위여야 합니다** (기본값 `local-demo-change-me`는 로컬 시연용).
- 막지 못하는 것: 비밀값과 서버를 모두 가진 사람(= 운영자 자신), 그리고 네트워크에서 주소 자체를 가로채는 공격(TLS가 필요).

⑬ **해지된 노드는 멈추고, 해지된 ID는 다시 쓰지 않는다** (2026-11-25 전체 점검에서 찾은 버그 두 개를 고침, etcd와 같은 규칙).
- 버그 1 — 해지한 노드를 다시 추가하면 60초 뒤 거부되거나, 더 나쁘게는 "가입됨"으로 나오는데 그 노드는 죽어 있었습니다.
  원인: Raft 로그에 그 ID의 해지 기록이 남아 있어, 같은 ID로 다시 들어온 노드는 로그를 따라가다 그 기록에서 "나는 해지됐다"로
  멈춥니다 (MicroRaft가 그렇게 동작하고, Raft 상태 파일을 지워도 같습니다). 죽은 노드가 투표 멤버로 올라가면 과반만 무거워집니다.
  고침: 해지된 ID로는 가입을 거부합니다 — "해지된 적이 있는 ID다 … 새 ID로 노드를 새로 켜서 추가한다". 같은 팀이 다시 들어올 때는
  **새 ID · 새 장부 · 새 키**로 켭니다 (예: D1 → D1b). 승격 전에 새 노드의 Raft가 실제로 돌고 있는지도 확인합니다.
- 버그 2 — 해지한 노드의 프로세스를 끄지 않고 두면 그룹이 흔들렸습니다 (실제로 임기 번호가 2 → 64로 오르고 리더가 여러 번 바뀜).
  원인: 리더는 해지된 노드에 로그를 더 보내지 않으므로 그 노드는 자기가 해지된 줄 모르고, 리더 소식이 끊겼다고 보고 계속 선거를
  겁니다. 임기 번호만 끝없이 오르다가 남은 멤버가 잠깐 리더를 놓친 순간 그 임기를 받아들이면 멀쩡한 리더가 물러납니다
  (Raft 논문 4.2.3절 "해지된 서버가 그룹을 방해하는 문제").
  고침: ① 자기 해지 기록을 적용한 노드는 멤버 변경이 끝나면 Raft를 멈춥니다. ② 남은 멤버는 해지된 ID가 보내는 Raft 메시지를 받지
  않고 HTTP 410으로 답합니다 — 꺼져 있는 동안 해지돼 그 사실을 모르는 노드도 다시 켜지면 이 답을 받고 멈춥니다. 해지된 노드의
  상태에는 "해지됨 — … 새 ID로 새 노드를 켠다"가 보입니다. 해지 ≠ 제외: 제외된 노드는 복구해서 돌아오고, 해지된 노드는 돌아오지 않습니다.
- 해지된 ID 명단은 스냅샷에도 들어갑니다 (`LedgerSnapshot.removed`) — 로그를 자른 뒤에도 유지됩니다.
- 확인 (2026-11-25, `node-rejoin-fix` 브랜치): `gradlew build` 118개 통과 (`MembershipChangeTest` 4 → 6, `MemberRegistryTest` 3 추가).
  켜져 있는 프로세스: D1 해지 후 프로세스를 켜 둠 → D1이 "해지됨"으로 멈추고 20초 동안 임기 · 리더 그대로 → D1 다시 추가는 1초 만에
  거부 → 팀 D가 새 노드 D1b로 가입 → 꺼 둔 동안 해지한 E1을 다시 켜도 멈춤 → 해지된 노드 둘이 켜져 있는 채로 리더를 꺼도 3.9초에 새 리더.

⑭ **조회가 몰려도 멈추지 않고, 장부가 길어져도 조회가 느려지지 않는다** (2026-11-25, 다른 PC의 FairChain으로 부하 테스트를 하다
A1이 30초씩 세 번 응답하지 않음. 프로세스는 살아 있었고 — `status`의 "꺼짐"은 3초 안에 답이 없다는 뜻 — 데이터 손실은 없었음).
- 버그 1 — 교착. 검증 요청은 응답을 다 보낼 때까지 DB 연결(노드마다 10개)을 쥐고 있었고(`open-in-view` 기본값), 장부를 읽은 뒤
  장부 잠금을 잠깐 잡습니다. 블록을 붙이는 스레드는 그 잠금을 쥔 채 DB 연결을 요청합니다. 연결 10개가 모두 검증 요청에 잡히면 서로를
  기다리며 멈추고, DB 연결 대기 시간(30초)이 지나야 풀립니다. FairChain이 노드에 차례로 묻기 때문에 요청이 A1에 먼저 몰렸습니다.
  고침: `spring.jpa.open-in-view: false` — 요청은 조회 하나가 끝날 때마다 연결을 돌려줍니다. 잠금을 기다리는 동안 연결을 쥐지 않습니다.
- 버그 2 — 조회마다 체인 전체 검사. 검증(`verify` · `verify-batch` · `proof`)이 요청마다 1번 블록부터 다시 읽었습니다.
  장부가 길수록 느려지고(6,261블록에서 노드 1대당 55~60ms), 그동안 DB 연결을 잡고 있어 버그 1을 부릅니다.
  고침: 검증은 **답하는 블록 하나만** 봅니다 — 블록 해시 재계산, 앞 블록 · 뒤 블록과의 연결 (`Ledger.blockIntact`). 어긋나면 그때
  전체 검사로 확정하고, 깨졌으면 그 뒤로는 어떤 조회도 통과시키지 않습니다. 기록 키로 최신 블록을 찾는 인덱스도 넣었습니다
  (`idx_block_record_key` — 검증과 블록 붙이기가 장부 전체를 훑지 않게).
- 버그 3 — H2의 결과 재사용. H2는 같은 연결에서 같은 조회를 되풀이하면 "그사이 데이터가 안 바뀌었다"고 보고 앞 결과를 다시 씁니다.
  다른 연결의 커밋과 겹치면 방금 커밋된 블록이 빠진 옛 결과가 그 연결에 남고, 블록을 붙이는 스레드가 옛 "마지막 블록"을 읽어 자기 블록을
  건너뜁니다 — 다른 노드에서 다시 받을 때까지 기록을 `BEHIND`로 거절합니다 (버그 1을 고친 뒤 같은 테스트에서 7번에 3번).
  고침: `SET OPTIMIZE_REUSE_RESULTS 0` (연결을 열 때마다, `application.yml`).
- 무엇이 무엇을 검사하나 (etcd도 무결성 검사를 켤 때와 주기적으로 하고, 요청마다 하지 않습니다):

  | 언제 | 범위 |
  |---|---|
  | 켤 때 | 체인 전체 — 해시 연결 + 블록마다 서명 |
  | 블록을 붙일 때 | 새로 붙는 구간 |
  | 검증 조회 | 답하는 블록 하나 — 해시 + 앞뒤 연결. 어긋나면 체인 전체 |
  | 원장 상태 조회 (점검 화면 · `status`) | 체인 전체 — 해시 연결 |
  | 순회 검사 (옵션) | 1초에 N블록씩 돌아가며 — 해시 연결 + 서명 |
  | FairChain이 다수와 다른 답을 신고 | 판정에서 체인 전체 + 서명 |

  달라진 점: 돌고 있는 노드의 **다른** 블록이 고쳐진 것은 검증 조회에서 바로 드러나지 않습니다. 그 블록을 물을 때, 원장 상태 조회,
  순회 검사, 다시 켤 때 드러납니다. 고친 블록 자체를 물으면 전처럼 그 자리에서 드러납니다.
- 확인: `NodeClusterTest`에 테스트 2개 추가 — 원장 상태 조회 24개가 계속 도는 동안 리더가 블록 60개를 기록(고치기 전 코드에서는 첫 블록에서
  `Connection is not available … active=10`으로 실패 — A1 로그와 같은 오류), 검증이 답하는 블록만 보는지와 고친 블록을 잡는지.
  `gradlew build` 120개 통과.
- 측정 (임시 노드 3대, PC 1대, 팔로워에 검증 1건씩 150번의 중앙값 · 한 번씩 잰 값):

  | 장부 길이 | 고치기 전 | 고친 뒤 |
  |---|---|---|
  | 500블록 | 5.7 ms | 2.6 ms |
  | 2,000블록 | 11.5 ms | 1.4 ms |
  | 4,000블록 | 22.8 ms | 3.2 ms |

  고치기 전에는 장부 길이에 비례해 늘고, 고친 뒤에는 길이와 상관없습니다. 기록 속도는 비슷합니다 (4,000블록까지 86 → 74건/초,
  한 번 잰 값이라 차이가 있다고 말하기는 어렵습니다). 예전 jar로 만든 장부(200블록)를 새 jar로 이어서 켜 20건을 더 기록하는 것도 확인했습니다.
- 켜져 있는 노드에 적용: `raft_nodes.py`는 jar를 처음 한 번만 `data/raft/node.jar`로 복사합니다. 장부를 그대로 두고 바꾸려면
  `gradlew :node:bootJar` → `raft_nodes.py stop` → `data/raft/node.jar` 삭제 → `raft_nodes.py start`.
- 멈춘 순간을 잡는 도구: `python scripts/node_stall_watch.py` — 노드가 2.5초 넘게 답하지 않으면 그 순간의 스레드 덤프를
  `data/raft/stall-dumps/`에 남기고 누가 DB 연결 · 장부 잠금을 기다리는지 요약합니다 (`--once`: 지금 한 번).
- FairChain 쪽에 남은 것 (윤도균): 검증할 때 노드에 차례로 묻는 것을 동시에 묻기 (`NodeVerificationRequester` ·
  `BlockRecordRequester.verifyRecord`). 지금은 한 노드가 늦으면 그 뒤 노드가 모두 기다리고, 노드가 늘수록 검증 시간이 더해집니다.

## 4. 실행

```
gradlew :node:bootJar :fairchain:bootJar
python scripts/raft_nodes.py start --fresh            # PC 1대: 리더 층 3대 + 팀원 6대
python scripts/raft_nodes.py status
python scripts/raft_nodes.py scenario                 # 시연 순서 전체 자동 확인
```

노드 늘리기 · 줄이기 (다른 서버의 노드도):

```
# 처음 그룹을 켤 때 다른 PC에서 접속할 수 있는 주소로 (localhost로 시작하면 다른 PC의 노드가 붙지 못함)
python scripts/raft_nodes.py start --fresh --host 192.168.0.11
# 새 노드를 켤 PC에서: 프로세스만 켠다 (팀원도 같이 켜려면 --members 2)
python scripts/raft_nodes.py new-node D1 --team D --host 192.168.0.14 --secret <비밀값> \
       --leaders A1=http://192.168.0.11:9001,B1=http://192.168.0.11:9002,C1=http://192.168.0.11:9003
# 가입: FairChain 점검 화면 → 블록체인 노드 → 노드 추가에 http://192.168.0.14:9001  (또는 add 명령)
python scripts/raft_nodes.py add http://192.168.0.14:9001
# 해지: 점검 화면의 해지 버튼 (또는)
python scripts/raft_nodes.py leave D1
# 해지한 팀을 다시 넣을 때는 새 ID로 새 노드를 켠다 (해지된 ID는 다시 쓰지 않는다 — 3장 ⑬)
python scripts/raft_nodes.py new-node D1b --team D --port 9005
```

리더 층 노드 수는 3 · 5 · 7처럼 홀수가 낫습니다 (4대는 3대와 똑같이 1대 장애만 견디면서 과반이 3대로 늘어남).
읽기 사본을 늘리는 것이면 리더 층이 아니라 팀원(`--members`)을 늘립니다 — 과반에 들어가지 않아 기록 속도에 영향이 없습니다.

정함(2026-11-23): FairChain의 조회 다수결을 2/3에서 **과반**으로 맞췄습니다 (`Majority.quorum`). 2/3이면 5대일 때 기록은 2대가
꺼져도 되는데 조회는 4대가 같은 답을 해야 해서 1대까지만 견뎠습니다. 지금은 기록이 되는 동안에는 조회도 됩니다. 노드 수는 홀수(3 · 5 · 7 · 9)로 늘립니다.

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

### 6-1. 노드 추가 · 해지 (`node-scaling` 브랜치, `integration` 위에서)

| 파일 | 바뀐 것 |
|---|---|
| `block/requester/NodeDirectory` (새 파일) | 지금 묻는 노드 목록. 설정의 주소는 출발점, 그 뒤는 리더의 멤버 명단(투표 멤버)을 따름. 따로 도는 스레드에서 3초마다 — 공용 `@Scheduled` 스레드를 쓰면 꺼진 노드를 기다리는 동안 입장 허용 · 블록 기록이 밀림 |
| `common/config/NodeConfig` | `List<HttpRequester> nodeRequesters` 빈 → `NodeDirectory` 빈 |
| `block/requester/BlockRecordRequester` · `NodeVerificationRequester` · `NodeDivergenceReporter` | 고정 목록 대신 `NodeDirectory`. 요청 하나를 처리하는 동안은 처음 받은 목록 하나만 씀 (도중에 목록이 바뀌어도 번호가 어긋나지 않게). 신고는 노드 순서와 그 목록을 같이 넘김 |
| `block/requester/NodeLedgerRequester` · `NodeLedgerAccess` | `blocks(번호, …)` → `blocks(주소, …)`, `NodeLedger`에 `id` 추가 (꺼진 노드도 해지할 수 있게) |
| `block/requester/NodeMembershipRequester` (새 파일) | 노드 추가(주소만) · 해지 요청을 리더부터 차례로 전달 |
| `inspect/NodeManageController` (새 파일) | `GET /dashboard/nodes/access` · `POST /dashboard/nodes` · `DELETE /dashboard/nodes/{id}`. 토큰은 `X-Dashboard-Token` 헤더, 설정에 적은 운영자만 |
| `inspect/InspectorService` | `NodeView`에 `id`, `blocks` 호출을 주소로 (2줄) |
| `common/config/FairChainProperties` | `Nodes`에 `managers` |
| `static/inspector.html` · `js/console.js` · `css/console.css` | 노드 추가 칸 · 해지 버튼 · `sendWithToken`. "다른 노드" 표시를 노드 수와 무관하게 |
| `static/demo.html` · `js/demo.js` · `demo/DemoController` | "과반(2대 이상)" 고정 문구 → 지금 노드 수로 계산한 과반 (조회만, 그대로) |
| 테스트 | `NodeDirectoryTest`(8) · `NodeManageControllerTest`(4) 추가, `NodeReportingTest` 시그니처 맞춤 |

노드 쪽은 `MembershipService`(가입 전 확인 · 주소만으로 가입 · 팀마다 1대), `NodeAuthFilter`(확인 경로만 비밀값 없이), `security/JoinProof`(새 파일),
`NodeKeys`(임의 바이트 서명), `PeerClient`(가입 요청은 오래 기다려 전달), `NodeSettings` · `MemberReplicaService`(나중에 가입한 팀의 팀원),
node-api의 `NodeJoinApi` · `JoinChallengeDto` · `JoinProofDto`. 테스트: `MembershipChangeTest` 2 → 4 (가짜 노드 3종이 거부되고 그 주소로
비밀값이 나가지 않음, 팀마다 1대), `JoinProofTest`(5).

확인한 것 (2026-11-23): `gradlew build` 106개 통과 (node-api 3 · node 44 · fairchain 41 · fairchain-api 5 · example-server 13).
켜져 있는 프로세스로는 PC 1대에서 노드 주소를 이 PC의 IP로 주고 확인했습니다 (다른 PC는 아직): 점검 화면에서 `http://<IP>:9004` 추가 →
"D1 (팀 D) 가입: learner → 투표 멤버", FairChain이 다시 켜지 않고 4대로 → 예매 5건 확정 · 체크인 5/5 · 위조 티켓 TAMPERED →
가짜 4종(노드가 아닌 주소 · 다른 비밀값의 노드 · 노드가 아는 주소와 다른 주소 · 팀원 노드) 거부 → 꺼 둔 D1을 화면에서 해지 →
3대로 발행 · 예매 · 체크인 → 3팀에서 하나 더 빼기 거부.

## 7. 설계명세서에서 고칠 곳

- 노드 합의 시퀀스(SD-0302-03 등): 제안 → 결정 → 확정 3단계 → "FairChain → 노드 → (팔로워면 리더에게 전달) → 리더가 블록 생성 ·
  Raft 로그 복제 → 과반 저장 → 확정 → 노드마다 서명 · 저장 → 팀원 전달" 로
- 클래스 다이어그램(노드): `NodeService` 아래에 `RaftNodeHolder` · `BlockStateMachine` · `HttpRaftTransport` · `Ledger` ·
  `NodeKeys` · `DivergenceService` · `RecoveryService` · `MembershipService` · `TeamLeaderService` · `MemberReplicaService`
- 판정 시퀀스 새로: 신고 → 뒤처짐/갈라짐 → 이진 탐색 → 서명 검증 → 제외(로그) → #k부터 복구 → 복귀(로그)
- node-api 계약: 새 API (`NodeRaftApi` · `NodeReportApi` · `NodeAdminApi` · `NodeMemberApi` · `NodeTeamApi` · `NodeJoinApi`), 사라진 API
  (`proposals` · `decision` · `confirm` · `recovery`)
- 노드 추가 시퀀스 새로: 운영자(점검 화면) → FairChain → 리더 → 새 노드에 가입 전 확인(nonce → HMAC · 서명) → 명단 등록(로그) →
  learner 추가 → 따라잡기 → 투표 멤버 승격 → FairChain 노드 목록 갱신

## 8. 남은 일

- PC 3대 측정 (지금 방식 · Raft 같은 조건) → 하트비트 시간 제한 값 확정 (미정사항 7번)
- 노드 추가를 실제로 다른 PC에서 확인 (지금까지는 PC 1대에서 이 PC의 IP 주소로만 확인함), 리더 층 3 · 5 · 7대일 때 기록 속도 측정
- 해지된 팀의 팀원 노드: 지금은 그대로 남아 다른 리더 층 노드에서 블록을 계속 받는다 — 같이 끌지 정하기
- 팀 회의: 위 3장 ① ② ③을 그대로 갈지, 담당 · 일정 (11/12 답변 끝)
- "시간이 되면" 항목: 블록에 기록 여러 건, 팀 리더 자동 교체, 노드 간 메시지 서명 · 기관 과반 승인 — 손대지 않음
