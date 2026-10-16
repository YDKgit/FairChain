# example-server

FairChain 예제서버 (콘서트 예매 사이트) — 백엔드 REST API + SSE. 화면은 없다.

## 실행

### FairChain 없이 (fairchain-mock)
메모리 가짜 FairChain(`requester/mock`)으로 전체 흐름이 돈다. 시드: 운영자 `admin / admin1234`, 스태프 `staff / staff1234`, 콘서트 2개(1번은 예매 오픈 · 공연 중, 2번은 미래 오픈), 등급 VIP · R.

MySQL 없이 H2로 (테스트 클래스패스 사용):
```bash
./gradlew :example-server:bootTestRun --args="--spring.profiles.active=test,fairchain-mock --example.outbox.dispatch-interval-ms=2000 --example.outbox.pending-delay=2s"
```

MySQL로:
```bash
cp example-server/src/main/resources/application-example.yml example-server/src/main/resources/application-local.yml   # 값 채우기
./gradlew :example-server:bootRun --args="--spring.profiles.active=local,fairchain-mock"
```

### 실제 FairChain과
`application-local.yml`의 `fairchain.base-url · api-key · client-id`를 채우고 `./gradlew :example-server:bootRun` (기본 프로필 `local`).

### 테스트
```bash
./gradlew :example-server:test
./gradlew :example-server:test --tests "com.fairchain.example.ScenarioTest"   # 아래 시나리오 전체
```

## 시나리오 (curl)
쿠키 파일로 세션을 구분한다(`-c` 저장, `-b` 사용). id는 시드 기준 값이고, 응답에서 확인한다.

```bash
B=http://localhost:8080; J='Content-Type: application/json'

# 1. 회원가입 (201, 중복이면 409)
curl -X POST $B/members -H "$J" -d '{"loginId":"alice","password":"password1","name":"앨리스","contact":"010-1111-2222"}'
curl -X POST $B/members -H "$J" -d '{"loginId":"bob","password":"password1","name":"밥","contact":"010-3333-4444"}'

# 2. 로그인 (실패 401)
curl -c alice.txt -X POST $B/login -H "$J" -d '{"loginId":"alice","password":"password1"}'
curl -c bob.txt   -X POST $B/login -H "$J" -d '{"loginId":"bob","password":"password1"}'
curl -c admin.txt -X POST $B/admin/login -H "$J" -d '{"loginId":"admin","password":"admin1234"}'
curl -c staff.txt -X POST $B/staff/login -H "$J" -d '{"loginId":"staff","password":"staff1234"}'

# 3. 콘서트 · 등급 확인 (seatGrades[].seatGradeId)
curl "$B/concerts"
curl $B/concerts/1

# 4. (운영자) 좌석 등록 → 좌석마다 ISSUE 기록. 운영자 세션 아니면 403
curl -b admin.txt -X POST $B/admin/seats -H "$J" -d '{"concertId":1,"seatGradeId":1,"seatNumbers":["A-1","A-2","A-3"]}'
curl -b admin.txt $B/admin/keys

# 5. 대기열 진입 → SSE로 순번 수신 (ADMITTED 받으면 스트림 종료). 미래 공연은 403
curl -b alice.txt -X POST $B/queue/1/enter
curl -N -b alice.txt $B/queue/1/subscribe
curl -b bob.txt -X POST $B/queue/1/enter
curl -N -b bob.txt $B/queue/1/subscribe

# 6. 예매 (업무상 실패도 200 + success=false)
curl -b alice.txt -X POST $B/reservations -H "$J" -d '{"seatId":1,"concertId":1}'

# 7. 티켓 목록 — 직후 PENDING, Outbox 재전송 후 VERIFIED
curl -b alice.txt "$B/tickets?eventId=1"
curl -b alice.txt $B/tickets/1

# 8. 매진 후 취소 → 재배정 시작 (mock 전용: 재시작 없이 매진 전환)
curl -X PUT "$B/mock/sold-out?value=true"
curl -b alice.txt -X POST $B/tickets/1/cancel
curl $B/concerts/1/seats            # A-1 = CANCELLED (재배정 중)

# 9. bob 재배정 제안 조회(없으면 204) → 제안 토큰으로 예매 (3분 안에)
curl -b bob.txt $B/reallocation/1/offer
curl -b bob.txt -X POST $B/reservations -H "$J" -d '{"seatId":1,"concertId":1,"offerToken":"<offerToken>"}'

# 10. 체크인 — 스태프 세션 없으면 401, 두 번째 스캔은 "이미 입장한 티켓"
curl -b bob.txt "$B/tickets?eventId=1"   # qrToken 확인
curl -b staff.txt -X POST $B/staff/scan -H "$J" -d '{"qrToken":"<qrToken>"}'

# 11. 로그아웃 (모든 대기열 이탈)
curl -b alice.txt -X POST $B/logout
```

## 엔드포인트

| 기능 | 메서드 · 경로 | 세션 |
|---|---|---|
| 회원가입 · 로그인 · 로그아웃 | `POST /members` · `POST /login` · `POST /logout` | 로그아웃은 회원 |
| 내 정보 · 수정 · 비번 변경 · 탈퇴 | `GET/PATCH/DELETE /members/me` · `PATCH /members/me/password` | 회원 |
| 대기열 진입 · 구독(SSE) · 이탈 | `POST /queue/{eventId}/enter` · `GET /queue/{eventId}/subscribe` · `DELETE /queue/{eventId}` | 회원 |
| 좌석 목록 | `GET /concerts/{concertId}/seats` | — |
| 예매 (일반 · 재배정) | `POST /reservations` | 회원 |
| 취소 | `POST /tickets/{ticketId}/cancel` | 회원 |
| 내 재배정 제안 | `GET /reallocation/{concertId}/offer` | 회원 |
| 내 티켓 목록 · 상세 | `GET /tickets?eventId=` · `GET /tickets/{ticketId}` | 회원 |
| 스태프 로그인 · QR 스캔 | `POST /staff/login` · `POST /staff/scan` | 스캔은 스태프(401) |
| 운영자 로그인 · 로그아웃 | `POST /admin/login` · `POST /admin/logout` | — |
| 키 조회 · 좌석 등록 · 스태프 등록 | `GET /admin/keys` · `POST /admin/seats` · `POST /admin/staff` | 운영자(403) |
| 콘서트 등록 · 수정 · 삭제 | `POST /admin/concerts` · `PUT/DELETE /admin/concerts/{concertId}` | 운영자(403) |
| 콘서트 목록 · 상세 | `GET /concerts?keyword=&venue=&from=&to=` · `GET /concerts/{concertId}` | — |
| (mock) 매진 전환 · 기록 보기 | `PUT /mock/sold-out?value=` · `GET /mock/records` | — |

오류 응답은 `{ "code": "...", "message": "..." }`. FairChain 429 → 429, 그 외 FairChain 실패 → 502.
