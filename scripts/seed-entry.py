#!/usr/bin/env python3
"""입장(체크인)을 바로 해 볼 수 있는 티켓을 만든다 (실행 중인 로컬 서버 대상).

  python3 scripts/seed-entry.py [공연ID=1]

오늘 공연(1번)에 좌석이 없으면 32석을 등록하고, 시연 회원 김철수 · 이영희 · 박민수가 한 장씩 예매한다.
입장은 공연 시작 3시간 전부터 되므로 오늘 공연이어야 게이트에서 허용된다.
"""
import http.cookiejar, json, sys, time, urllib.request
EX = 'http://' + __import__('os').environ.get('EX_HOST', 'localhost') + ':8080'; CON = int(sys.argv[1]) if len(sys.argv) > 1 else 1
class C:
    def __init__(s): s.o=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def call(s,m,p,b=None):
        r=urllib.request.Request(EX+p,data=json.dumps(b or {}).encode() if m!='GET' else None,method=m,headers={'Content-Type':'application/json'})
        try: t=s.o.open(r,timeout=60).read().decode()
        except urllib.error.HTTPError as e: t=e.read().decode()
        return json.loads(t) if t.strip()[:1] in '{[' else {}
op=C(); op.call('POST','/api/operator/login',{'loginId':'operator','password':'operator1234'})
c=op.call('GET',f'/api/concerts/{CON}')
if not c['totalSeats']:
    for i,g in enumerate(c['seatGrades']):
        rows=['A','B'] if i==0 else ['C','D']
        print(g['gradeName'], len(op.call('POST','/api/operator/seats',{'concertId':CON,'seatGradeId':g['id'],'seatNumbers':[f'{r}{n}' for r in rows for n in range(1,9)]}).get('registered',[])))
seats=[x for x in C().call('GET',f'/api/concerts/{CON}/seats')['seats'] if x['status']=='EMPTY']
got=[]
for i,who in enumerate(['chulsoo','younghee','minsu']):
    m=C(); m.call('POST','/api/members/login',{'loginId':who,'password':'demo1234'})
    m.call('POST',f'/api/queue/{CON}')
    for _ in range(40):
        if m.call('GET',f'/api/queue/{CON}/status').get('state')=='ADMITTED': break
        time.sleep(0.4)
    for _ in range(6):                     # 요청 제한(429)에 걸리면 잠깐 쉬고 다시
        r=m.call('POST','/api/reservations',{'seatId':seats[i]['seatId'],'concertId':CON})
        if r.get('success'): break
        time.sleep(1.5)
    print(who, seats[i]['seatNumber'], r)
    got.append((who,m,r.get('ticketId')))
time.sleep(6)
for who, m, t in got:
    k = m.call('GET', f'/api/tickets/{t}') if t else {}
    print(who, k.get('seatNumber'), k.get('status'), k.get('verificationStatus'))
