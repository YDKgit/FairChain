#!/usr/bin/env python3
"""JMeter 결과(.jtl)를 단계별 건수 · 실패 · 평균/최대 응답 시간으로 요약한다:  python3 loadtest/summarize.py 결과.jtl"""
import csv,collections,sys
d=collections.defaultdict(lambda:[0,0,0,0]); why=collections.Counter(); t0=None;t1=0; enter=[]
for r in csv.DictReader(open(sys.argv[1],encoding='utf8',errors='replace')):
    x=d[r['label']]; e=int(r['elapsed'] or 0); x[0]+=1; bad=r['success']!='true'; x[1]+=bad; x[2]+=e; x[3]=max(x[3],e)
    ts=int(r['timeStamp']); t0=ts if t0 is None else min(t0,ts); t1=max(t1,ts+e)
    if r['label']=='A-1 대기열 진입': enter.append(ts)
    if bad: why[(r['label'][:24],r['responseCode'],r['responseMessage'][:60])]+=1
for k,(n,e,t,m) in d.items(): print(f'{n:7d} err={e:6d} avg={t//n:6d}ms max={m:6d}ms  {k}')
for k,v in why.most_common(8): print(v,k)
print('total sec',(t1-t0)/1000,'enter spread sec',(max(enter)-min(enter))/1000 if enter else None)
