#!/usr/bin/env python3
"""공연에 좌석을 가지런한 배치로 등록한다 (실행 중인 로컬 서버 대상).

  python3 scripts/seed-seats.py [공연ID=2] [줄 수=20] [한 줄 좌석 수=30]

줄 이름은 A, B, C … 이고 번호는 1부터다. 앞쪽 40% 줄은 첫 번째 등급, 나머지는 두 번째 등급으로 넣는다.
기본값이면 A~T 20줄 x 30석 = 600석. 이미 좌석이 있는 공연에는 넣지 않는다.
"""
import http.cookiejar, json, sys, time, urllib.request

EX = 'http://localhost:8080'
CONCERT = int(sys.argv[1]) if len(sys.argv) > 1 else 2
ROWS = int(sys.argv[2]) if len(sys.argv) > 2 else 20
PER_ROW = int(sys.argv[3]) if len(sys.argv) > 3 else 30

opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(method, path, body=None):
    data = json.dumps(body if body is not None else {}).encode() if method != 'GET' else None
    req = urllib.request.Request(EX + path, data=data, method=method, headers={'Content-Type': 'application/json'})
    try:
        with opener.open(req, timeout=300) as r:
            text = r.read().decode()
    except urllib.error.HTTPError as e:
        text = e.read().decode()
    return json.loads(text) if text.strip().startswith(('{', '[')) else {}


def row_name(i):            # 0 → A, 25 → Z, 26 → AA
    name = ''
    i += 1
    while i > 0:
        i -= 1
        name = chr(65 + i % 26) + name
        i //= 26
    return name


call('POST', '/api/operator/login', {'loginId': 'operator', 'password': 'operator1234'})
concert = call('GET', f'/api/concerts/{CONCERT}')
for _ in range(40):                      # 서버가 막 켜졌으면 기본 공연이 아직 안 들어가 있을 수 있다
    if 'seatGrades' in concert:
        break
    time.sleep(0.5)
    call('POST', '/api/operator/login', {'loginId': 'operator', 'password': 'operator1234'})
    concert = call('GET', f'/api/concerts/{CONCERT}')
else:
    sys.exit(f'공연 {CONCERT}을(를) 읽지 못했습니다: {concert}')
if concert.get('totalSeats'):
    sys.exit(f"공연 {CONCERT}에는 이미 좌석이 {concert['totalSeats']}석 있습니다 — 넣지 않습니다")
grades = concert['seatGrades']
front = ROWS if len(grades) == 1 else max(1, round(ROWS * 0.4))
for grade, rows in ((grades[0], range(0, front)), (grades[-1], range(front, ROWS))):
    names = [f'{row_name(r)}{n}' for r in rows for n in range(1, PER_ROW + 1)]
    if names:
        count = 0
        for k in range(0, len(names), 150):          # 서버가 한 번에 받는 좌석 수에 한도가 있어 나눠 보낸다
            done = call('POST', '/api/operator/seats', {'concertId': CONCERT, 'seatGradeId': grade['id'], 'seatNumbers': names[k:k + 150]})
            if 'registered' not in done:
                sys.exit(f"좌석 등록 실패: {done}")
            count += len(done['registered'])
        print(f"  {grade['gradeName']}석: {row_name(rows[0])}~{row_name(rows[-1])}줄 {count}석")
concert = call('GET', f'/api/concerts/{CONCERT}')
print(f"공연 {CONCERT}: {concert['title']} — 좌석 {concert['emptySeats']}/{concert['totalSeats']}")
