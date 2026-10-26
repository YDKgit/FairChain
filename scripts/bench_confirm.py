"""블록 확정 속도 측정 — FairChain을 거쳐 기록 N건이 모두 CONFIRMED가 될 때까지 걸린 시간.

지금 체크아웃된 브랜치의 jar(node · fairchain)를 그대로 띄우므로, 브랜치마다 빌드한 뒤 같은 명령으로 돌려 비교한다.
  demo-page      : 노드끼리 투표 (FairChain → 노드 1대 → 나머지 노드에 제안 · 투표)
  fairchain-vote : FairChain이 노드 3대에 동시에 보내고 다수결 (노드끼리 통신 없음)

준비: gradlew :node:bootJar :fairchain:bootJar
실행: python scripts/bench_confirm.py [--records 200] [--java D:\\Java\\bin\\java.exe]

Redis는 scripts/dev-redis.py(메모리 대체 서버)를 띄우고, DB는 임시 폴더의 H2 파일을 쓴다. 측정이 끝나면 모두 끈다.
1) 좌석 N석 발행(ISSUE) 요청 1번 → N건 확정까지
2) 예매(PURCHASE) N건을 연달아 요청 → N건 확정까지
"""
import argparse, glob, hashlib, json, os, pathlib, shutil, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent


def jar(module):
    found = glob.glob(str(ROOT / module / 'build' / 'libs' / f'{module}-*.jar'))
    if not found:
        sys.exit(f'{module} jar가 없습니다. 먼저 gradlew :{module}:bootJar')
    return found[0]


def h(*fields):
    """Sha256RecordHasher와 같은 방식: 필드마다 "길이:값", null은 -1:"""
    text = ''.join('-1:' if f is None else f'{len(str(f))}:{f}' for f in fields)
    return hashlib.sha256(text.encode()).hexdigest()


def call(method, url, body=None, headers=None, timeout=15):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors='replace')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--records', type=int, default=200)
    ap.add_argument('--java', default=os.path.join(os.environ['JAVA_HOME'], 'bin', 'java') if os.environ.get('JAVA_HOME') else 'java')
    ap.add_argument('--base-port', type=int, default=19900)
    a = ap.parse_args()

    n, base = a.records, a.base_port
    nodes = [base + 1, base + 2, base + 3]
    fc_port, redis_port = base + 10, base + 11
    secret, api_key, client = 'bench-' + uuid.uuid4().hex[:8], 'bench-key-' + uuid.uuid4().hex[:8], 'bench-client'
    work = pathlib.Path(tempfile.mkdtemp(prefix='fairchain-bench-'))
    procs = []

    def spawn(tag, args, env=None):
        log = open(work / f'{tag}.log', 'wb')
        procs.append((subprocess.Popen(args, cwd=work, env={**os.environ, **(env or {})},
                                       stdout=log, stderr=subprocess.STDOUT), log))

    def wait_ok(check, what, seconds=120):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            try:
                if check():
                    return
            except OSError:
                pass
            time.sleep(0.3)
        sys.exit(f'{what}이(가) 켜지지 않았습니다. 로그: {work}')

    fc = f'http://127.0.0.1:{fc_port}'
    key_header = {'X-API-KEY': api_key}
    try:
        spawn('redis', [sys.executable, str(ROOT / 'scripts' / 'dev-redis.py'), str(redis_port)])
        for i, port in enumerate(nodes, 1):
            peers = ','.join(f'http://127.0.0.1:{p}' for p in nodes if p != port)   # 노드끼리 투표하는 브랜치용, 다른 브랜치는 무시
            spawn(f'node{i}', [a.java, '-Xmx256m', '-jar', jar('node')],
                  {'NODE_PORT': str(port), 'NODE_DB': str(work / f'node{i}'), 'NODE_SECRET': secret, 'NODE_PEERS': peers})
        for port in nodes:
            wait_ok(lambda p=port: call('GET', f'http://127.0.0.1:{p}/node/ledger/status', headers={'X-Node-Secret': secret})[0] == 200,
                    f'노드 :{port}')
        urls = ','.join(f'http://127.0.0.1:{p}' for p in nodes)
        spawn('fairchain', [a.java, '-Xmx512m', '-jar', jar('fairchain'), f'--server.port={fc_port}',
                            f'--fairchain.nodes.urls={urls}', f'--fairchain.nodes.api-key={secret}',
                            f'--spring.data.redis.port={redis_port}', f'--fairchain.seed.client-id={client}',
                            f'--fairchain.seed.api-key={api_key}', '--fairchain.seed.operator-login-id=bench',
                            f'--fairchain.seed.operator-password=bench-{uuid.uuid4().hex[:8]}'],
              {'DB_URL': f'jdbc:h2:file:{work / "fairchain"};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE', 'DB_USERNAME': 'sa', 'DB_PASSWORD': ''})
        wait_ok(lambda: call('GET', f'{fc}/api/demo/events/1/records', headers=key_header)[0] == 200, 'FairChain')

        event = 1
        seat_key = {s: h(client, s) for s in range(1, n + 1)}

        def confirmed():
            s, rows = call('GET', f'{fc}/api/demo/events/{event}/records', headers=key_header)
            return {r['recordKey']: r for r in rows} if s == 200 else {}

        def wait_confirmed(version):
            while True:
                rows = confirmed()
                done = sum(1 for k in seat_key.values()
                           if rows.get(k, {}).get('version') == version and rows[k]['blockState'] == 'CONFIRMED')
                if done == n:
                    return
                time.sleep(0.1)

        def report(label, seconds):
            print(f'{label:<28} {n}건 {seconds:6.2f}초  → 초당 {n / seconds:6.1f}건, 1건당 {seconds * 1000 / n:6.1f} ms', flush=True)

        issue = [{'type': 'ISSUE', 'eventId': event, 'userId': None, 'slotKey': f'A-{s}', 'recordKey': seat_key[s],
                  'version': 1, 'recordHash': h(event, s, None, 'EMPTY', 1)} for s in range(1, n + 1)]
        t0 = time.perf_counter()
        status, body = call('POST', f'{fc}/api/admin/seats/issue', {'eventId': event, 'records': issue}, key_header)
        if status != 200:
            sys.exit(f'좌석 발행 실패 {status} {body}')
        wait_confirmed(1)
        report('1) 좌석 발행 (ISSUE)', time.perf_counter() - t0)

        t0 = time.perf_counter()
        for s in range(1, n + 1):
            member = 1000 + s
            status, body = call('POST', f'{fc}/api/records', {
                'type': 'PURCHASE', 'eventId': event, 'userId': member, 'slotKey': f'A-{s}', 'recordKey': seat_key[s],
                'version': 2, 'recordHash': h(event, s, member, 'ACTIVE', 2)}, key_header)
            if status != 200:
                sys.exit(f'예매 실패 {status} {body}')
        wait_confirmed(2)
        report('2) 예매 (PURCHASE)', time.perf_counter() - t0)

        heads = {call('GET', f'http://127.0.0.1:{p}/node/ledger/status', headers={'X-Node-Secret': secret})[1]['headHash'] for p in nodes}
        print(f'노드 3대 마지막 블록 해시 일치: {len(heads) == 1}')
    finally:
        for p, log in procs:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(p.pid), '/T', '/F'], capture_output=True)
            else:
                p.terminate()
            p.wait()
            log.close()
        shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    main()
