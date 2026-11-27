#!/usr/bin/env python3
"""부하 테스트용 공연을 새로 하나 만들고 좌석을 넣는다 (실행 중인 로컬 서버 대상). 마지막 줄에 공연 번호만 출력한다.

  [TITLE=이름] python3 loadtest/new-concert.py [줄 수=20] [한 줄 좌석 수=30]

테스트를 한 번 돌리면 그 공연은 매진되고 회원마다 표가 한 장씩 생긴다. 서버를 다시 켜는 대신
돌릴 때마다 새 공연을 만들면 좌석 · 대기열 · 회원의 구매 이력이 모두 깨끗한 상태에서 시작한다.
"""
import datetime, http.cookiejar, json, os, pathlib, subprocess, sys, urllib.request

EX = 'http://' + __import__('os').environ.get('EX_HOST', 'localhost') + ':8080'      # 예제서버가 다른 PC에 있으면 EX_HOST=그 PC의 IP
ROWS = sys.argv[1] if len(sys.argv) > 1 else '20'
PER_ROW = sys.argv[2] if len(sys.argv) > 2 else '30'
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(path, body):
    req = urllib.request.Request(EX + path, data=json.dumps(body).encode(), method='POST', headers={'Content-Type': 'application/json'})
    try:
        with opener.open(req, timeout=60) as r:
            text = r.read().decode()
    except urllib.error.HTTPError as e:
        text = e.read().decode()
    return json.loads(text) if text.strip().startswith('{') else {}


now = datetime.datetime.now().replace(microsecond=0)
TITLE = os.environ.get('TITLE') or f'부하 테스트 {now:%H:%M:%S}'      # TITLE=test1 로 이름을 정할 수 있다
call('/api/operator/login', {'loginId': 'operator', 'password': 'operator1234'})
made = call('/api/operator/concerts', {
    'title': TITLE,
    'eventOpen': (now - datetime.timedelta(minutes=1)).isoformat(),      # 이미 예매 오픈된 상태
    'eventStart': (now + datetime.timedelta(days=30)).replace(hour=19, minute=0, second=0).isoformat(),
    'eventEnd': (now + datetime.timedelta(days=30)).replace(hour=21, minute=0, second=0).isoformat(),
    'venue': '부하 테스트',
    'seatGrades': [{'gradeName': 'R', 'price': 143000}, {'gradeName': 'S', 'price': 88000}],
})
if 'id' not in made:
    sys.exit(f'공연을 만들지 못했습니다: {made}')
seed = pathlib.Path(__file__).resolve().parent.parent / 'scripts' / 'seed-seats.py'
done = subprocess.run([sys.executable, str(seed), str(made['id']), ROWS, PER_ROW], capture_output=True, text=True)
if done.returncode != 0:
    sys.exit(f'좌석을 넣지 못했습니다: {done.stdout}{done.stderr}')
print(f"새 공연 #{made['id']} '{TITLE}' — 좌석 {int(ROWS) * int(PER_ROW)}석", file=sys.stderr)
print(made['id'])
