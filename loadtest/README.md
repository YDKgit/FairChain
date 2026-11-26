# 부하 테스트 시나리오 (JMeter)

예제 예매 사이트(FairTicket)에 사용자가 몰릴 때 **대기열 · 좌석 예매 · 블록 기록 · 매크로 차단**이 설계대로 동작하는지 확인한다.
요청은 실제 사용자와 같은 길로 간다: JMeter → 예제서버(8080) → FairChain(8081) → 노드(9001~).

| 파일 | 내용 |
|---|---|
| `fairticket.jmx` | JMeter 5.6 테스트 계획 (시나리오 A~D) |
| `build_jmx.py` | 위 파일을 만드는 스크립트. 시나리오를 고칠 때는 이 파일을 고치고 `python3 loadtest/build_jmx.py` |
| `JMETER-공부.md` | JMeter 개념을 우리 시나리오에 맞춰 풀어 쓴 글 (처음 보는 사람은 이것부터) |
| `run-slow.sh` · `run-waves.sh` · `run-multi.sh` | 한 명씩 일정 간격 · 묶음으로 나눠서 · 1만 명 동시. 돌릴 때마다 공연을 새로 만들어 서버를 다시 켜지 않아도 된다 (사용법은 각 파일 맨 위) |
| `new-concert.py` | 테스트용 공연과 좌석을 만든다 |
| `collect.py` | 실행 하나의 결과 · 서버 로그를 `summary.md`와 zip 하나로 묶는다 (4장 "결과 묶음") |

## 1. 준비

```
./scripts/run-local.sh --mem          # macOS · Linux: 빈 DB로 전체 실행 (Windows: scripts\run-local.ps1)
```

- **매번 빈 상태에서 시작한다.** 앞선 실행의 예매 · 대기열 기록이 남아 있으면 수치가 달라진다. `--mem`은 DB와 노드 장부를 메모리 · 새 장부로 띄운다
- 테스트 계획이 스스로 준비한다: 운영자로 로그인해 모자란 좌석을 등록하고, 회원은 실행할 때마다 새로 가입한다. 손으로 넣을 데이터는 없다
- 대상 공연은 **예매가 열려 있는 공연**이어야 한다. 기본값 2번(2026 SUMMER NIGHT CONCERT)은 공연이 한 달 뒤라 취소표 재배정 조건과 겹치지 않는다

## 2. 실행

```
jmeter -n -t loadtest/fairticket.jmx -l loadtest/results/run1.jtl -e -o loadtest/results/run1-report
```

- `-n` 화면 없이 실행 (수치를 잴 때는 반드시 이 방식), `-l` 결과 기록, `-e -o` 실행이 끝나면 HTML 보고서를 만든다 (`run1-report/index.html`)
- 결과 폴더가 이미 있으면 JMeter가 거절한다. 실행마다 이름을 바꾼다 (`run2`, `run3` …). `loadtest/results/`는 git에 올라가지 않는다
- 시나리오를 눈으로 보거나 고칠 때만 화면으로 연다: `jmeter -t loadtest/fairticket.jmx`

값은 `-J이름=값`으로 바꾼다.

| 이름 | 기본 | 뜻 |
|---|---|---|
| `users` | 100 | A: 예매 오픈에 몰리는 사람 수 |
| `rampUp` | 5 | A: 준비 단계의 가입과 A의 로그인을 이 초 동안 나눠서 한다 (대기열 진입은 모두 로그인한 뒤 동시에, 최대 10분 기다림) |
| `queueWait` | 0 | A · B: 대기열에서 입장 허용을 기다리는 최대 초. 0이면 입장할 때까지 기다린다 (입장해야 재배정 명단에 오른다). 요청이 30번 연달아 실패하면 그 사람은 포기한다 |
| `tag` · `enterAt` | (없음) · 0 | JMeter를 여러 개 띄울 때: `tag`는 회원 아이디에 붙는 구분 글자(프로세스마다 다르게), `enterAt`은 모두가 대기열에 들어갈 시각(ms). macOS는 프로세스 하나가 스레드를 약 6천 개까지만 만들 수 있어 6천 명을 넘기려면 나눠 띄운다 |
| `premade` · `userOffset` | false · 0 | `premade=true`면 가입 단계를 건너뛰고 서버가 켜질 때 만들어 둔 회원을 쓴다 (`EXAMPLE_LOAD_MEMBERS=15000 ./scripts/run-local.sh --mem`). 수천 명일 때 준비 시간이 몇 분에서 몇 초로 준다. `userOffset`은 JMeter를 여러 개 띄울 때 회원 번호의 시작 위치 |
| `seats` | 150 | 준비: 예매 가능한 좌석이 이보다 적으면 모자란 만큼 등록 |
| `raceUsers` | 20 | B: 같은 좌석 하나를 동시에 노리는 사람 수 |
| `macroUsers` · `macroLoops` | 1 · 100 | C: 매크로 회원 수 · 한 명이 연속으로 보내는 요청 수 |
| `browseUsers` · `browseSeconds` | 0 · 30 | D: 조회만 하는 사람 수 · 시간. 0명이면 건너뛴다 |
| `concertId` | 2 | 대상 공연 |
| `host` · `port` | localhost · 8080 | 예제서버 주소 |
| `operatorId` · `operatorPw` | operator · operator1234 | 좌석 등록에 쓰는 운영자 계정 (`run-local.sh` 기본값) |

사람 수를 0으로 주면 그 시나리오는 실행되지 않는다. 예) A만: `-JraceUsers=0 -JmacroUsers=0`

## 3. 시나리오

시나리오는 **준비 → B → A → C → D → 판정** 순서로 하나씩 실행된다 (서로의 수치에 섞이지 않게).

### 준비 — 좌석 등록

운영자로 로그인 → 공연 확인 → 예매 가능한 좌석이 `seats`보다 적으면 한 줄에 20석씩 등록 → 좌석 목록에서 B에서 쓸 좌석 하나를 정한다.
좌석 등록은 좌석마다 FairChain에 발행(ISSUE) 기록을 남기므로, 이 단계가 곧 **블록 기록 일괄 처리**의 부하다.

### A. 예매 오픈 폭주 (핵심)

> 예매가 열리는 순간 `users`명이 한꺼번에 들어온다. 대기열이 순번대로 들여보내고, 모두가 좌석을 하나씩 잡고, 티켓이 블록체인에 기록되어 검증 완료가 된다.

| 단계 | 요청 | 설명 |
|---|---|---|
| A-0 | `POST /api/members/login` | 준비 단계에서 미리 만든 회원으로 로그인 (가입은 준비 단계 `준비: A 회원 미리 만들기`가 한다) |
| A-1 | `POST /api/queue/{공연}` | **모든 사람이 이 자리에 모일 때까지 기다렸다가 동시에** 보낸다 |
| A-1 | `GET /api/queue/{공연}/status` | 1초마다, 상태가 `ADMITTED`가 될 때까지 (`queueWait`를 주지 않으면 제한 없음) |
| A-2 | `GET /api/concerts/{공연}/seats` | 0.3~1초 생각한 뒤 예매 가능한 좌석 중 하나를 무작위로 고른다 |
| A-3 | `POST /api/reservations` | 다른 사람이 먼저 잡았으면 A-2로 돌아가 다시 고른다 (최대 5번) |
| A-4 | `GET /api/tickets/{티켓}` | 0.5초마다, `verificationStatus`가 `VERIFIED`가 될 때까지 (최대 60초) |

**볼 것**
- `A-1 대기 순번 확인` 횟수 ÷ 사람 수 = 한 사람이 대기한 평균 초. FairChain이 1초에 100명씩 들여보내므로(`fairchain.queue.admit-per-second`, 2026-11-22에 10 → 100) 6,000명이면 마지막 사람이 약 60초 기다린다
- `A-3 예매`의 응답 시간 — 예매는 FairChain에 판매 기록을 보내고 바로 답한다 (블록 확정을 기다리지 않음)
- `A-4 예매 후 블록체인 검증 완료까지` — 예매 직후부터 노드 합의가 끝나 티켓이 검증될 때까지. **0.5초 간격으로 확인하므로 0.5초보다 작은 차이는 보이지 않는다**
- `A-3 예매 — 선점 실패 (다시 고름)` 줄이 생기면 두 사람이 같은 좌석을 고른 것이다. 실패가 아니라 정상 동작이다

**통과 기준**
- 오류 0건 (선점 실패 · 매진은 오류로 세지 않는다)
- 좌석이 사람 수보다 많으면 `A-3 예매` 성공 = `users`명, 모두 `A-4`에서 검증 완료
- 실행 뒤 `python3 scripts/raft_nodes.py status`에서 모든 노드의 마지막 해시가 같다

### B. 같은 좌석 동시 예매

> 입장한 `raceUsers`명이 **같은 좌석 하나**를 같은 순간에 예매한다.

가입 · 로그인 → 대기열 통과 → 모두 모이면 준비 단계에서 정한 좌석으로 `POST /api/reservations`를 동시에 보낸다.

**통과 기준**: `B-2 같은 좌석 예매 — 성공`이 **정확히 1건**, 나머지는 `— 거절 (정상)`. 마지막의 `B 판정` 줄이 이 기준을 검사해 어긋나면 실패로 표시한다.

### C. 매크로 (한 회원의 반복 요청)

> 회원 한 명이 쉬지 않고 대기열 요청을 `macroLoops`번 보낸다.

FairChain은 회원 한 명의 요청을 1초에 5번까지만 받는다(`fairchain.rate-limit.per-second`). 넘는 요청은 거절되어야 한다.

**볼 것 · 통과 기준**
- `C-1 대기열 반복 요청 — 통과`는 1초에 5건 이하, 나머지는 `— 차단됨 (정상)`
- 차단된 요청의 응답 시간이 통과한 요청보다 길지 않다 (차단이 서버에 부담을 주지 않는다)
- 이 시나리오가 도는 동안 A를 같이 돌려도 다른 회원의 예매가 느려지지 않는지 보려면 5장의 "함께 돌리기"를 쓴다

### D. 둘러보기 (조회만)

> 로그인하지 않은 `browseUsers`명이 `browseSeconds`초 동안 공연 목록 · 상세 · 좌석 현황을 본다.

FairChain을 거치지 않는 요청이라, 예제서버 자체의 기준 응답 시간을 잰다. A의 수치와 비교하면 FairChain · 노드를 거치는 데 드는 시간을 알 수 있다.

## 4. 결과 읽는 법

HTML 보고서의 **Statistics** 표에서 줄 이름(Label)이 위 단계 이름이다.

| 열 | 뜻 |
|---|---|
| #Samples | 요청 수 |
| FAIL · Error % | 실패한 요청 (기대한 거절은 실패로 세지 않는다) |
| Average · 90th · 95th · 99th pct | 응답 시간(ms). 평균보다 95% 값을 본다 — "100명 중 95명은 이 시간 안에 답을 받았다" |
| Throughput | 초당 처리한 요청 수 |

줄 이름 끝의 꼬리표: `— 성공`, `— 거절 (정상)`, `— 선점 실패 (다시 고름)`, `— 매진이라 포기`, `— 통과`, `— 차단됨 (정상)`.
`… 준비: 회원가입 + 로그인`, `A-4 예매 후 블록체인 검증 완료까지`는 여러 요청을 묶은 값이라 기다린 시간이 포함된다.

### 결과 묶음 (Claude에게 보내 분석받기)

`run-slow.sh` · `run-waves.sh` · `run-multi.sh`는 실행마다 새 폴더(`loadtest/results/slow-1126-2105` 처럼 이름 뒤에 날짜 · 시각)를 만들고,
끝나면 **`loadtest/results/<폴더>.zip`** 하나로 묶는다. 이 zip을 그대로 Claude에게 보내면 된다.

| zip 안 | 내용 |
|---|---|
| `summary.md` | 단계별 건수 · 실패 · 응답 시간(평균 · 50/95/99% · 최대), 시간대별 처리량, 실패 원인, 노드 상태(시작 전 → 끝난 뒤, 임기가 바뀌었으면 선거가 있었다는 표시), 서버 로그 요약 |
| `*.jtl` · `jmeter*.log` · `out*.txt` | JMeter 원본 |
| `server-logs/` | 테스트 시간(앞뒤 30초 포함)의 FairChain · 예제서버 · 노드 로그만 잘라 낸 것 |
| `run.json` | 시작 시각 · PC · 커밋 · 시작 전 노드 상태 |

jmeter를 손으로 돌릴 때는 앞뒤로 부른다:

```
python3 loadtest/collect.py begin loadtest/results/run1 "설명"
jmeter -n -t loadtest/fairticket.jmx -l loadtest/results/run1/all.jtl -j loadtest/results/run1/jmeter.log
python3 loadtest/collect.py end loadtest/results/run1
```

노드가 다른 PC에 있으면 그 주소를 알려 준다: `NODES=http://IP:9001,http://IP:9002,http://IP:9003 ./loadtest/run-slow.sh …`
(비밀값이 기본값과 다르면 `NODE_SECRET=…`도). 그 PC의 노드 로그는 거기서 모은다 — `summary.md` 끝에 시각까지 넣은 명령이 나온다:
`python scripts/collect_logs.py --from "2026-11-26 21:05:00" --to "2026-11-26 21:12:00"` → `data/logs/bundles/logs-<PC>-<시각>.zip`.

서버 로그가 남는 곳 (`scripts/collect_logs.py`가 여기서 모은다):

| 파일 | 내용 |
|---|---|
| `data/logs/fairchain.log` · `example.log` | FairChain · 예제서버 (`run-local.sh` · `run-local.ps1`) |
| `data/logs/nodes/<ID>.log` | 노드 로그 + 5초마다 상태 한 줄: 역할 · 임기 · 리더 · 블록, 요청 수(기록 · 검증 · Raft · 상태), 가장 느린 요청, 처리 중 요청, DB 연결 사용 · 대기, 힙 · 스레드 · GC · CPU |
| `data/logs/nodes/<ID>-access.날짜.log` | 노드가 받은 요청마다 한 줄 (시각 · 요청 · 상태 · 걸린 ms) |
| `data/logs/nodes/<ID>-threads-시각.txt` | 노드가 멈춘 것 같을 때(DB 연결 대기 3초 이상 · 10초 넘게 끝나지 않는 요청) 자동으로 남는 스레드 덤프 |

## 5. 더 해 볼 것

| 실험 | 방법 | 보려는 것 |
|---|---|---|
| 규모 키우기 | `-Jusers=300 -Jseats=350`, `-Jusers=1000 -Jseats=1050` | 사람 수가 늘 때 응답 시간 · 검증 완료 시간이 어떻게 늘어나는지 |
| 매진 | `-Jusers=200 -Jseats=100` (빈 상태에서) | 좌석보다 사람이 많을 때: 100명 성공, 나머지는 `매진이라 포기`, 오류 0건 |
| 대기열 속도 | FairChain을 `FAIRCHAIN_OPTS=--fairchain.queue.admit-per-second=50 ./scripts/run-local.sh --mem`으로 띄움 | 들여보내는 속도를 올리면 좌석 경합(선점 실패)이 얼마나 늘어나는지 |
| 팀원 노드 | `./scripts/run-local.sh --mem --members 2` (노드 9대) 와 `--members 0` (3대) 비교 | 팀원 사본이 검증 완료 시간에 주는 영향 |
| 리더 장애 중 예매 | A를 돌리는 중에 다른 터미널에서 `python3 scripts/raft_nodes.py kill-leader` | 리더가 바뀌는 4~5초 동안 예매는 계속 받고, 검증 완료만 늦어지는지 |
| 함께 돌리기 | JMeter 화면에서 테스트 계획의 "Run Thread Groups consecutively" 체크를 끔 | 매크로 · 조회 부하가 있는 상태에서의 예매 응답 시간 |

## 6. 기준 측정값 (2026-11-17)

조건: MacBook 1대에 전부 (JMeter 포함), `run-local.sh --mem --members 2` (리더 층 3대 + 팀원 6대), Redis는 시연용 대체 서버, 기본값 + `-JbrowseUsers=20 -JbrowseSeconds=20`.
전체 2,054건 · 오류 0건 · 41초.

| 단계 | 건수 | 평균 | 95% | 최대 |
|---|---|---|---|---|
| A-1 대기열 진입 (100명 동시) | 100 | 36ms | 48ms | 50ms |
| A-1 대기 순번 확인 | 550 | 7ms | 30ms | 43ms |
| A-3 예매 | 100 | 9ms | 18ms | 25ms |
| A-4 예매 후 블록체인 검증 완료까지 | 100 | 533ms | 553ms | 1,042ms |
| B-2 같은 좌석 예매 | 20 | 성공 1 · 거절 19 | | |
| C-1 대기열 반복 요청 | 100 | 통과 2 · 차단 98 | | |
| D 공연 목록 · 상세 · 좌석 | 682 | 1ms | 3ms | 4ms |

실행 뒤 노드 9대의 높이 299, 마지막 해시 모두 같음.

## 7. 주의

- **한 컴퓨터에서 잰 값은 참고용이다.** JMeter · 서버 · 노드 9대가 같은 CPU를 나눠 쓴다. 발표에 쓸 수치는 JMeter를 다른 컴퓨터에서 돌리거나 PC 3대 구성에서 다시 잰다
- **Redis**: `run-local.sh`는 진짜 `redis-server`를 띄운다 (2026-11-22부터. 그 전 기록은 시연용 대체 서버로 잰 것). Windows `run-local.ps1`은 6379에 Redis가 없으면 아직 대체 서버를 띄우므로, 수치를 잴 때는 Redis를 먼저 띄워 둔다
- **매크로 차단의 응답 코드**: FairChain은 429로 거절하지만 예제서버는 이를 `502` + `"FairChain: 요청이 너무 많습니다"`로 전달한다. 그래서 시나리오 C는 본문 문구로 차단을 판별한다. 예제서버가 429를 그대로 전달하도록 바꾸면 `build_jmx.py`의 `MACRO_RESULT`도 같이 고친다
- **좌석이 없는 공연은 FairChain에 등록되지 않은 공연이다.** 준비 단계가 좌석을 등록하기 전에는 대기열 요청이 `502 등록되지 않은 공연`으로 거절된다
- 테스트가 만든 회원(`a시각_번호` …)과 좌석(`L`로 시작하는 줄)은 DB에 남는다. `--mem`으로 띄웠으면 끄면 사라진다

## 2026-11-22 기록 — 동시 진입 인원을 늘려 본 결과

조건: MacBook 1대에 전부 (JMeter 포함), 진짜 Redis, `run-local.sh --mem` (리더 층 3대), 입장 초당 100명, 대상 공연 600석 (`scripts/seed-seats.py 2 20 30`),
시나리오 A만 (`-JraceUsers=0 -JmacroUsers=0`). 6천 명은 JMeter 1개, 1만 · 1만 5천 명은 `loadtest/run-multi.sh`로 2 · 3개.

| 인원 | 대기열 진입 | 예매 · 블록 검증 | 대기 순번 확인 실패 | 노드 |
|---|---|---|---|---|
| 6,000 | 전원, 0.5초 안에 | 600석 전부 검증 완료 | 0건 | 재선출 없음, 장부 일치 |
| 10,000 (2회) | 전원(1회는 1명 503), 0.9초 안에 | 600석 전부 검증 완료 (평균 3.3~3.4초) | 3.0% · 3.7% | 재선출 없음, 장부 일치 |
| 15,000 (2회) | 전원, 1.5초 안에 | 600석 판매, 60초 안에 검증 확인은 일부만 | 24% · 32% | 재선출 20회 · 3회, 장부 일치 |

- 1만 명 이상에서 실패의 대부분은 JMeter 쪽 `BindException: Can't assign requested address` — 한 컴퓨터가 한 서버로 열 수 있는 연결(임시 포트 약 1만 6천 개)이 바닥난 것이다. 요청이 서버에 닿기 전에 실패한 것이라 FairChain의 한계가 아니다. 서버가 거절한 503은 1만 명에서 0.2~0.3%
- 이 구성에서 **오류 없이 잴 수 있는 것은 6천 명**까지다. 더 큰 수치는 JMeter를 다른 컴퓨터에서 돌려야 한다
- 이 테스트로 고친 것
  - 예제서버 → FairChain 통신이 요청마다 새 연결을 맺어, 6천 명에서 순번 확인의 43%가 포트 부족으로 실패 → 연결 재사용 (`RestHttpRequester`)
  - FairChain 주기 작업(입장 허용 · 순번 전송 · 대시보드 · 블록 기록)이 스레드 하나를 같이 써서, 블록 기록이 노드를 기다리면 입장도 멈춤 → `spring.task.scheduling.pool.size: 4`
- 남은 것
  - 1만 5천 명에서 FairChain DB 연결(기본 10개)이 바닥나 요청 175건이 실패했다 — 연결 수를 늘려 다시 재 볼 것
  - 200명 확인 실행에서 노드 A1의 DB 연결이 바닥나 티켓 검증이 시간 초과된 일이 한 번 있었다 (재현 조건 모름)
