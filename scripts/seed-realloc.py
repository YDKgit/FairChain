#!/usr/bin/env python3
"""취소표 재배정을 해 볼 수 있는 상태를 만든다 (실행 중인 로컬 서버 대상).

  python3 scripts/seed-realloc.py [공연ID=5] [취소 장수=3]

가짜 관객(fanNN)이 남은 좌석을 전부 예매해 매진시키고, 그중 몇 명이 취소해 취소표를 '보류' 상태로 둔다.
시연 회원 김철수 · 이영희 · 박민수는 줄만 서고 표를 못 산 사람으로 남아 재배정 명단에 오른다.
"""
import http.cookiejar, json, sys, time, urllib.request

EX = 'http://' + __import__('os').environ.get('EX_HOST', 'localhost') + ':8080'      # 예제서버가 다른 PC에 있으면 EX_HOST=그 PC의 IP
CONCERT = int(sys.argv[1]) if len(sys.argv) > 1 else 5
CANCEL = int(sys.argv[2]) if len(sys.argv) > 2 else 3
WAITERS = ['chulsoo', 'younghee', 'minsu']


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None):
        data = json.dumps(body if body is not None else {}).encode() if method != 'GET' else None
        req = urllib.request.Request(EX + path, data=data, method=method, headers={'Content-Type': 'application/json'})
        try:
            with self.opener.open(req, timeout=15) as r:
                text = r.read().decode()
        except urllib.error.HTTPError as e:
            text = e.read().decode()
        return json.loads(text) if text.strip().startswith(('{', '[')) else {}


def member(login_id, name=None):
    c = Client()
    if name:
        c.call('POST', '/api/members', {'loginId': login_id, 'password': 'demo1234', 'name': name, 'contact': '010-0000-0000'})
    c.call('POST', '/api/members/login', {'loginId': login_id, 'password': 'demo1234'})
    return c


def admitted(c):
    c.call('POST', f'/api/queue/{CONCERT}')
    for _ in range(40):
        if c.call('GET', f'/api/queue/{CONCERT}/status').get('state') == 'ADMITTED':
            return True
        time.sleep(0.4)
    return False


guest = Client()
info = guest.call('GET', f'/api/concerts/{CONCERT}')
print(f"공연 {CONCERT}: {info['title']} — 남은 좌석 {info['emptySeats']}/{info['totalSeats']}")

for login in WAITERS:                      # 줄만 서고 표는 안 사는 사람 (명단 후보)
    print(f'  {login} 줄 서기:', '입장' if admitted(member(login)) else '실패')

empty = [s['seatId'] for s in guest.call('GET', f'/api/concerts/{CONCERT}/seats')['seats'] if s['status'] == 'EMPTY']
fans, stamp = [], int(time.time()) % 100000
for i, seat in enumerate(empty, 1):
    c = member(f'fan{stamp}x{i:02d}', f'관객{i:02d}')
    if not admitted(c):
        print('  입장 실패', i); continue
    for attempt in range(5):               # 요청 제한(429)에 걸리면 잠깐 쉬고 다시
        r = c.call('POST', '/api/reservations', {'seatId': seat, 'concertId': CONCERT})
        if r.get('success'):
            fans.append((c, r.get('ticketId')))
            break
        time.sleep(1.2)
    else:
        print('  예매 실패', i, json.dumps(r, ensure_ascii=False)[:120])
    if i % 11 == 0:
        print(f'  예매 {len(fans)}/{len(empty)}')
print('매진 여부: 남은 좌석', guest.call('GET', f'/api/concerts/{CONCERT}')['emptySeats'])

print('블록 확정을 기다리는 중…')
# 마지막 예매까지 FairChain에 기록돼야 '매진'으로 잡힌다. 그 전에 취소하면 취소표가 보류되지 않고 바로 풀린다
for _ in range(600):
    c, tid = fans[-1]
    if c.call('GET', f'/api/tickets/{tid}').get('verificationStatus') == 'VERIFIED':
        break
    time.sleep(1)
CANCEL = min(CANCEL, len(fans))
for c, tid in fans[:CANCEL]:
    c.call('POST', f'/api/tickets/{tid}/cancel')
time.sleep(3)
state = member(WAITERS[0]).call('GET', f'/api/reallocation/concerts/{CONCERT}')
print('취소', CANCEL, '장 →', json.dumps(state, ensure_ascii=False)[:300])
