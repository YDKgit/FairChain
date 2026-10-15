# 로컬 전체 실행 · 시연

예제서버(예매 사이트) · FairChain · 노드 3대를 한 번에 띄우고, 화면으로 요청과 기록을 확인한다.

## 실행

JDK 17+ · Python 3 필요. 프로젝트 루트에서 (Windows PowerShell):

```powershell
.\scripts\run-local.ps1           # 빌드 후 전부 실행 (서버마다 창 하나)
.\scripts\stop-local.ps1          # 전부 종료
```

| 옵션 | 뜻 |
|---|---|
| `-NoBuild` | 빌드 없이 실행 |
| `-NoQueue` | 예제서버가 대기열을 거치지 않음 (바로 좌석 선택, 입장 권한 확인 생략) |
| `-MySql` | FairChain DB를 MySQL로 (`application-local.yml` 필요). 기본은 H2 파일 `data\fairchain` |
| `-NoBrowser` | 브라우저를 열지 않음 |

- Redis가 6379에 없으면 시연용 대체 서버(`scripts\dev-redis.py`)를 띄운다. 부하 테스트에는 진짜 Redis를 쓸 것
- 계정 · 키는 처음 실행할 때 만들어 `data\local-secrets.json`에 저장하고, 실행이 끝나면 창에 출력한다. `data\`는 git에 올라가지 않는다
- 처음부터 다시 하려면 `stop-local.ps1` 후 `data\` 폴더를 지운다

| 주소 | 내용 | 로그인 |
|---|---|---|
| http://localhost:8080 | 예제 예매 사이트 | 회원: 직접 가입 · 운영자: `operator` · 게이트: `staff` |
| http://localhost:8081/inspector.html | FairChain 점검 화면 | FairChain 운영자 `admin` |
| http://localhost:8081/dashboard.html | 관제 대시보드 | FairChain 운영자 `admin` |
| :9001 · :9002 · :9003 | 노드 3대 | — |

## 시연 순서

1. **운영자** 화면 → 좌석 사전 등록 (예: `A1-A10`) → FairChain에 ISSUE 기록, 노드 3대에 블록 생성
2. 회원 가입 → 공연 → **예매하기** → 대기열 → 입장 → 좌석 선택 → 예매
3. **내 티켓** — "블록 기록 중" → 몇 초 뒤 "블록체인 검증 완료" (블록 번호 표시)
4. **게이트** 화면 → 내 티켓의 QR 토큰 입력 → 입장 허용 → 같은 토큰을 다시 넣으면 "이미 입장한 티켓"
5. 매진 후 취소 → 대기했지만 못 산 회원의 **내 티켓**에 재배정 제안 (3분 안에 예매)
6. **FairChain 점검 화면**
   - 받은 요청: 예제서버가 보낸 `/api` 요청과 응답 본문
   - FairChain DB: `event` · `anchor_record` · `admission`, 블록 기록 대기 줄
   - 블록체인 노드: 노드마다 최근 블록 (다른 노드와 다른 블록은 빨갛게)

## 구성

| | 포트 | DB |
|---|---|---|
| 예제서버 (`example-server`) | 8080 | H2 `data\example` (MySQL은 `EXAMPLE_DB_URL`) |
| FairChain (`fairchain`) | 8081 | H2 `data\fairchain` 또는 MySQL + Redis |
| 노드 (`node`) ×3 | 9001–9003 | H2 `data\node1`–`node3` |

예제서버는 `fairchain-api`의 인터페이스 · `RestHttpRequester`로 FairChain을 호출한다 (`X-API-KEY`). 판매 · 취소 · 입장 기록은 `record_outbox`에 먼저 저장해 두고 보내므로 FairChain이 잠시 꺼져 있어도 잃지 않는다.
