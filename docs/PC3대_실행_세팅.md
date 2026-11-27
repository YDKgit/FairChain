# PC 3대로 나눠 실행하기 · 초기 세팅

PC 3대에 서버를 나눠 띄우고, 시연 · 부하 테스트에 필요한 초기 데이터를 넣는 방법이다.
2026-11-25에 이 구성으로 실제로 돌려 본 내용을 적었다.

| 역할 | 띄우는 것 | 포트 | 2026-11-25의 IP |
|---|---|---|---|
| 노드 PC | 노드 3대 (A1 · B1 · C1) | 9001 ~ 9003 | 172.30.67.90 |
| FairChain PC | Redis · FairChain · JMeter | 6379 · 8081 | 172.30.71.22 |
| 예제서버 PC | 예제서버 + 화면 (웹 · Expo 앱) | 8080 | 172.30.67.66 |

IP는 와이파이에 다시 붙으면 바뀐다. 아래 명령의 `노드IP` · `FAIRCHAIN_IP` · `예제IP` 자리에 그날 값을 넣는다.

## 0. 준비 (세 명 공통)

- 세 PC가 같은 와이파이에 있어야 한다. 기기끼리 통신이 막힌 와이파이면 휴대폰 핫스팟에 세 대를 붙인다.
- `demo-1123` 브랜치를 `git pull` 한다.
- 자기 IP 확인: macOS `ipconfig getifaddr en0`, Windows `ipconfig`의 IPv4 주소.
- Windows는 처음 실행할 때 뜨는 방화벽 창에서 **허용**을 누른다.
- 노트북은 전원을 연결하고 **절전을 끈다**. 절전에 들어가면 그 PC의 서버가 통째로 멈춘다.

## 1. 켜는 순서

반드시 **노드 → FairChain → 예제서버** 순서로 켠다. 셋 다 메모리 DB라서, 하나만 껐다 켜면 서로 기록이 맞지 않는다.
다시 켤 일이 생기면 셋 다 이 순서로 다시 켠다.

### 1-1. 노드 PC

Windows (FairChain 폴더에서):

```
.\gradlew :node:bootJar
python scripts\raft_nodes.py start --fresh --members 0 --host 노드IP --secret local-demo-change-me
```

`--host`에 이 PC의 IP를 꼭 넣는다. 빼면 노드가 자기 주소를 `localhost`로 알려 FairChain이 찾지 못한다.

### 1-2. FairChain PC

macOS:

```
cd ~/Desktop/FairChain && ./scripts/run-local.sh --mem --fairchain-only --node-host 노드IP
```

Redis와 FairChain만 뜬다. 노드에 닿지 않으면 경고가 나온다.

### 1-3. 예제서버 PC

Windows PowerShell (FairChain 폴더에서):

```
.\gradlew :example-server:bootJar
$env:FAIRCHAIN_URL='http://FAIRCHAIN_IP:8081'; $env:FAIRCHAIN_API_KEY='key-localdemo1234'; $env:EXAMPLE_DB_URL='jdbc:h2:mem:example;MODE=MySQL;DB_CLOSE_DELAY=-1'; $env:EXAMPLE_OPERATOR_PASSWORD='operator1234'; $env:EXAMPLE_STAFF_PASSWORD='staff1234'; $env:EXAMPLE_LOAD_MEMBERS='15000'; java -jar example-server\build\libs\example-server-0.0.1-SNAPSHOT.jar --server.tomcat.max-connections=40000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400
```

macOS:

```
./gradlew :example-server:bootJar && FAIRCHAIN_URL=http://FAIRCHAIN_IP:8081 FAIRCHAIN_API_KEY=key-localdemo1234 EXAMPLE_DB_URL='jdbc:h2:mem:example;MODE=MySQL;DB_CLOSE_DELAY=-1' EXAMPLE_OPERATOR_PASSWORD=operator1234 EXAMPLE_STAFF_PASSWORD=staff1234 EXAMPLE_LOAD_MEMBERS=15000 java -jar example-server/build/libs/example-server-0.0.1-SNAPSHOT.jar --server.tomcat.max-connections=40000 --server.tomcat.accept-count=10000 --server.tomcat.threads.max=400
```

이 값들을 빼고 켜면 운영자 비밀번호가 달라지고 부하 테스트용 회원이 만들어지지 않는다.

서버가 켜질 때 자동으로 만들어지는 것:

| 항목 | 값 |
|---|---|
| 운영자 | `operator` / `operator1234` |
| 게이트 스태프 | `staff` / `staff1234` |
| 시연 회원 5명 | 김철수(`chulsoo`) · 이영희(`younghee`) · 박민수(`minsu`) 등, 비밀번호 `demo1234` |
| 부하 테스트 회원 | `load1` ~ `load15000` / `load1234` |
| 공연 | 더미 공연 여러 개 (좌석은 아직 없음) |

## 2. 초기 데이터 넣기 (좌석 · 재배정 명단 · 입장용 티켓)

세 서버를 **새로 켠 직후**에 한 번 넣는다. 예매와 취소가 FairChain과 노드까지 기록되므로 세 서버가 모두 켜져 있어야 한다.

### 방법 A — 예제서버 PC에서 직접 (Windows, FairChain 폴더)

```
python scripts\seed-seats.py 2 20 30
python scripts\seed-seats.py 4 4 8
python scripts\seed-seats.py 5 4 8
python scripts\seed-seats.py 6 4 8
python scripts\seed-seats.py 7 4 8
python scripts\seed-seats.py 8 4 8
python scripts\seed-realloc.py 5 3
python scripts\seed-entry.py
```

### 방법 B — FairChain PC(macOS)에서 원격으로 한 줄

```
cd ~/Desktop/FairChain/scripts && export EX_HOST=예제IP && python3 seed-seats.py 2 20 30 && for c in 4 5 6 7 8; do python3 seed-seats.py $c 4 8; done && python3 seed-realloc.py 5 3 && python3 seed-entry.py
```

`EX_HOST`를 읽는 스크립트는 FairChain PC의 작업본에만 있다 (아직 커밋하지 않음).

### 각 명령이 하는 일

| 명령 | 하는 일 |
|---|---|
| `seed-seats.py 2 20 30` | 2번 공연에 600석 (20줄 x 30석, JMeter용) |
| `seed-seats.py 4~8 4 8` | 4 ~ 8번 공연에 32석씩 (일반 예매용) |
| `seed-realloc.py 5 3` | 5번 공연을 가짜 관객으로 매진시킨 뒤 3장을 취소해 취소표를 '보류'로 둔다. 김철수 · 이영희 · 박민수는 줄만 서고 표를 못 산 사람으로 남아 재배정 명단에 오른다 |
| `seed-entry.py` | 오늘 공연(1번)에 세 사람이 한 장씩 예매한다. 게이트 입장을 바로 해 볼 수 있다 |

## 3. 화면 주소

| 화면 | 주소 | 계정 |
|---|---|---|
| 예매 사이트 | `http://예제IP:8080` | 시연 회원 / `demo1234` |
| 운영자 | `http://예제IP:8080/admin` | `operator` / `operator1234` |
| 게이트 | `http://예제IP:8080/gate` | `staff` / `staff1234` |
| FairChain 관제 대시보드 | `http://FAIRCHAIN_IP:8081/dashboard.html` | `admin` / `admin1234` |
| FairChain 점검 화면 | `http://FAIRCHAIN_IP:8081/inspector.html` | `admin` / `admin1234` |

점검 화면에서는 노드 추가 · 해지가 되므로 측정 중에는 보기만 한다.

### 휴대폰 앱 (Expo Go)

예제서버 PC에서 켠다. 앱은 QR을 띄운 컴퓨터의 IP를 예제서버 주소로 쓴다.

```
cd example-server/mobile
npm install
npm start
```

- 컴퓨터의 Expo CLI와 휴대폰의 Expo Go가 **같은 계정이거나 둘 다 로그아웃** 상태여야 열린다. 컴퓨터 쪽 로그아웃은 `npx expo logout`.
- 다른 주소로 붙이려면 앱의 계정 화면 "예제서버" 칸에 `http://예제IP:8080`을 적는다.

## 4. 부하 테스트 (FairChain PC에서)

돌릴 때마다 예제서버에 새 공연을 만들어 거기서 돌리므로 서버를 다시 켤 필요가 없다.

```
cd ~/Desktop/FairChain && EX_HOST=예제IP TITLE=공연이름 ./loadtest/run-slow.sh 1000 0.1
```

| 스크립트 | 뜻 |
|---|---|
| `run-slow.sh 인원 간격초` | 한 명씩 일정한 간격으로 진입 (좌석 수는 `SEATS=1000`) |
| `run-waves.sh 총인원 묶음 간격초` | 묶음으로 나눠 진입 |
| `run-multi.sh 이름 프로세스수 프로세스당인원` | 같은 순간에 전원 진입 (6천 명 초과는 프로세스를 나눔) |

## 4-1. 부하 테스트 옵션 (2026-11-25 추가, FairChain PC의 작업본에만 있음)

명령 앞에 붙인다. 예) `EX_HOST=예제IP VERIFY=0 WAIT_MS=120000 CONNECT_MS=20000 TITLE=이름 ./loadtest/run-multi.sh 이름 2 5000`

| 옵션 | 기본 | 뜻 |
|---|---|---|
| `EX_HOST` | localhost | 예제서버 PC의 IP |
| `STAY` | 0 | 예매를 마친 뒤 머무는 시간(ms). 요청은 보내지 않는다 |
| `VERIFY` | 120 | 예매 뒤 티켓 확인(노드 검증 조회) 최대 횟수. 0이면 조회하지 않는다 |
| `WAIT_MS` · `CONNECT_MS` | 30000 · 5000 | 응답 · 연결을 기다리는 최대 시간(ms) |

1만 명을 돌리기 전에 FairChain PC(macOS)의 포트 범위를 넓힌다 (재부팅하면 원래대로):

```
sudo sysctl -w net.inet.ip.portrange.first=10000 net.inet.ip.portrange.hifirst=10000 net.inet.tcp.msl=1000
```

## 5. 2026-11-25에 확인한 것

| 조건 | 결과 | 결과 폴더 (`loadtest/results/`) |
|---|---|---|
| 1,000명, 0.1초 간격 (초당 10명) | 전 단계 오류 0. 예매 응답 95% 183ms, 무결성 검증 95% 335ms, 블록 확정 95% 0.86초 | `slow-1125-죽지마라노드` |
| 1,000명 동시 (장부 1,350 → 4,259블록) | 실패 다수 (대기열 진입 503, 예매 · 티켓 확인 30초 시간 초과) | `per06` · `per06b` |
| 10,000명 동시, 장부 7,461블록에서 시작 | 대기열 진입 10,000건 실패 0 (6초). 그 뒤 단계는 무너짐 | `t1234` |
| 10,000명 동시, 장부 830블록에서 시작 | 진입 실패 0, 600석 판매, 노드 정상. 실패의 85%가 JMeter PC의 포트 고갈 | `dontdie10000` |
| 10,000명 동시, 티켓 조회 없음 | 테스트 도중 노드 장부가 0으로 돌아가 무효 | `noverify10000` |
| **10,000명 동시, 티켓 조회 없음, 응답 2분까지 대기** | 진입 실패 0 (10초, 초당 1,000건), 600석 전부 판매(67초), 30초 시간 초과 0건. 서버 오류는 503 579건(도착한 요청 108,302건의 0.53%, 577명이 한 번씩 겪고 전원 회복). 나머지 실패 76,903건은 JMeter PC의 포트 고갈. 끝난 뒤 표를 산 600명을 따로 조회: 600장 전부 검증 완료, 무결성 검증 평균 128ms · 95% 260ms · 최대 317ms | `wait10000` |

명세서 기준: PER-03(1만 건, 초당 500건 이상, 진입 실패 0) · PER-07(검증 3초) · PER-08(확정 10초)은 충족.
PER-01(95% 2초)은 1만 명이 몰리는 처음 10 ~ 20초에 미충족(대기열 진입 95% 8.2초). PER-06은 포트 고갈을 풀고 다시 재야 판정할 수 있다.
PER-02 · PER-04 · PER-09는 아직 재지 않았다.

원인으로 확인한 것:

- **노드는 조회 한 번마다 장부 전체를 다시 검사한다** (`NodeService.batch` → `ledger.audit()`). 장부가 길수록 느려진다.
  같은 요청으로 잰 노드 한 대의 처리량: 맥 0블록 초당 3,011건 → 맥 7,500블록 175건, 노드 PC 7,461블록 93건.
- 서버를 끄지 않고 테스트를 이어 돌리면 장부가 계속 쌓인다. **수치를 잴 때는 세 서버를 새로 켜서 짧은 장부에서 시작한다.**
- 노드 A1이 30초씩 멈춘 것은 노드 안의 교착(검증 요청이 DB 연결을 쥐고, 블록을 붙이는 쪽이 장부 잠금을 쥔 채 연결을 기다림)이다. 노드 프로세스는 죽지 않았다 (노드 담당자 확인).
- FairChain은 노드 3대를 차례로 부르고 항상 A1에 먼저 보낸다. 요청이 A1에 몰린다.
- JMeter와 FairChain이 같은 PC에 있으면 포트 번호(기본 약 16,000개, 닫은 뒤 30초 묶임)를 나눠 쓴다. JMeter가 다 쓰면 FairChain이 노드로 보내는 연결도 `Can't assign requested address`로 막힌다.
- JMeter가 응답을 30초만 기다리면 느린 요청을 버리고 새 연결을 열어 포트 고갈을 키운다. 2분으로 늘리니 서버 쪽 시간 초과가 0건이 됐다.
- **노드를 `--fresh`로 다시 켜면 FairChain · 예제서버도 반드시 같이 다시 켠다.** 이날 세 번 이 일로 기록이 어긋났다.

구조에 대해 확인한 것 (바꾸지 않음, 팀 결정 대기):

- 대기열 입장은 정원제가 아니라 속도제다. 1초마다 맨 앞 100명을 들여보내고, 안에 몇 명이 있는지는 보지 않는다 (`QueueScheduler`). 입장 권한은 5분간 유효하다.
- 1인 1매는 예제서버(`ReservationService`)만 확인한다. FairChain은 한 회원이 몇 장 샀는지 세지 않는다.
