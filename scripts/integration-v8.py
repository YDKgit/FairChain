#!/usr/bin/env python3
"""Black-box v8 integration: 3 independent Node processes + FairChain.
Run from project root after: ./gradlew :node:bootJar :fairchain:bootJar
Requires Python 3.9+, Java 17+, no Python pip modules. Uses disposable temp H2 files.
"""
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.request, urllib.error, uuid

ROOT=pathlib.Path(__file__).resolve().parents[1]
NODE_JARS=list((ROOT/'node/build/libs').glob('node-*.jar'))
FAIR_JARS=list((ROOT/'fairchain/build/libs').glob('fairchain-*.jar'))
if len(NODE_JARS)!=1 or len(FAIR_JARS)!=1:
    sys.exit('ERROR: Expected one executable JAR per module. Run Gradle bootJar first. Found node=%d fairchain=%d' % (len(NODE_JARS),len(FAIR_JARS)))

BASE=int(os.getenv('V8_BASE_PORT','18080'))
SECRET='v8-test-node-secret-'+uuid.uuid4().hex
KEY='v8-test-client-'+uuid.uuid4().hex
PROCESSES=[]

def request(port,method,path,data=None,key=None):
    body=json.dumps(data).encode() if data is not None else None
    headers={'Content-Type':'application/json'}
    if key: headers['X-API-Key']=key
    else: headers['X-Node-Secret']=SECRET
    req=urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=body,headers=headers, method=method)
    with urllib.request.urlopen(req,timeout=12) as resp:
        return json.load(resp)

def wait(port):
    until=time.monotonic()+70
    while time.monotonic()<until:
        try:
            request(port,'GET','/node/ledger/status' if port!=BASE else '/api/blockchain/audit',key=KEY if port==BASE else None)
            return
        except Exception:
            if any(p.poll() is not None for p in PROCESSES):
                raise RuntimeError('One of the servers exited during startup; inspect process logs')
            time.sleep(.5)
    raise TimeoutError(f'Server {port} not ready within 70 seconds')

def ensure(condition, message):
    if not condition: raise AssertionError(message)
    print('PASS:',message)

with tempfile.TemporaryDirectory(prefix='fairchain-v8-') as tmp:
    logs=[]
    try:
        for i in range(3):
            port=BASE+i+1
            peers=','.join(f'http://127.0.0.1:{BASE+j+1}' for j in range(3) if j!=i)
            env={**os.environ,'NODE_PORT':str(port),'NODE_DB':str(pathlib.Path(tmp)/f'node{i+1}'), 'NODE_PEERS':peers,'NODE_SECRET':SECRET}
            log=open(pathlib.Path(tmp)/f'node{i+1}.log','wb'); logs.append(log)
            PROCESSES.append(subprocess.Popen(['java','-jar',str(NODE_JARS[0])],cwd=tmp,env=env,stdout=log,stderr=subprocess.STDOUT))
        for port in range(BASE+1,BASE+4): wait(port)
        env={**os.environ,'FAIRCHAIN_PORT':str(BASE),'NODE_URLS':','.join(f'http://127.0.0.1:{BASE+i+1}' for i in range(3)), 'NODE_SECRET':SECRET,'DEMO_API_KEY':KEY}
        log=open(pathlib.Path(tmp)/'fairchain.log','wb');logs.append(log)
        PROCESSES.append(subprocess.Popen(['java','-jar',str(FAIR_JARS[0])],cwd=tmp,env=env,stdout=log,stderr=subprocess.STDOUT))
        wait(BASE)
        audit=request(BASE,'GET','/api/blockchain/audit',key=KEY)
        ensure(audit['consistent'], 'All empty ledgers agree')
        record_key=uuid.uuid4().hex*2
        digest='b'*64
        payload={'type':'ISSUE','eventId':100,'userId':None,'slotKey':'A-1','recordKey':record_key,'version':1,'recordHash':digest}
        written=request(BASE,'POST','/api/anchors',payload,key=KEY)
        ensure(written['blockState']=='CONFIRMED' and written['blockIndex']==1,'ISSUE recorded and confirmed by FairChain')
        for port in range(BASE+1,BASE+4):
            result=request(port,'POST','/node/records/verify',{'recordKey':record_key,'version':1,'recordHash':digest})
            ensure(result['valid'],f'Node {port} verifies ISSUE')
        verified=request(BASE,'POST','/api/anchors/tickets/verify',{'recordKey':record_key,'version':1,'recordHash':digest},key=KEY)
        ensure(verified['valid'],'FairChain verifies ISSUE via all three nodes')
        bogus=request(BASE,'POST','/api/anchors/tickets/verify',{'recordKey':record_key,'version':1,'recordHash':'f'*64},key=KEY)
        ensure(not bogus['valid'],'Modified ticket fingerprint rejected')
        payload.update(type='PURCHASE',version=2,recordHash='c'*64)
        newer=request(BASE,'POST','/api/anchors',payload,key=KEY)
        ensure(newer['blockState']=='CONFIRMED' and newer['blockIndex']==2,'PURCHASE version 2 committed')
        payload.update(type='CHECKIN',version=3,recordHash='d'*64)
        checked=request(BASE,'POST','/api/anchors',payload,key=KEY)
        ensure(checked['blockState']=='CONFIRMED' and checked['blockIndex']==3,'CHECKIN version 3 committed')
        old=request(BASE,'POST','/api/anchors/tickets/verify',{'recordKey':record_key,'version':1,'recordHash':digest},key=KEY)
        ensure(not old['valid'],'Old ticket version rejected after new event')
        audit=request(BASE,'GET','/api/blockchain/audit',key=KEY)
        ensure(audit['consistent'] and all(x['status']['height']==3 for x in audit['nodes']),'Three nodes agree on chain height 3')
        print('ALL V8 INTEGRATION CHECKS PASSED')
    except Exception as exc:
        print('FAILED:',exc,file=sys.stderr)
        for name in ('node1.log','node2.log','node3.log','fairchain.log'):
            path=pathlib.Path(tmp)/name
            if path.exists():
                print('=== '+name+' (tail) ===',file=sys.stderr)
                print(path.read_text(errors='replace')[-3000:],file=sys.stderr)
        sys.exit(1)
    finally:
        for p in PROCESSES:
            if p.poll() is None: p.terminate()
        for p in PROCESSES:
            try: p.wait(timeout=5)
            except subprocess.TimeoutExpired: p.kill();p.wait()
        for log in logs: log.close()
