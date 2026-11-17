"""노드만 측정 — FairChain 없이 노드에 기록 N건을 하나씩 보내 확정까지 걸린 시간을 잰다.

이미 켜져 있는 노드에 보내므로 합의 방식(demo-page의 노드 간 투표, raft-node의 Raft)과 상관없이 같은 명령으로 비교한다.
FairChain처럼 한 건이 확정된 뒤에 다음 건을 보낸다.

  python scripts/bench_nodes.py --urls http://localhost:9001,http://localhost:9002,http://localhost:9003 --records 300 --runs 3

--target 은 기록을 보낼 노드 번호(1부터). 그 노드가 거부하거나 응답하지 않으면 FairChain처럼 다음 노드로 넘긴다.
"""
import argparse, json, statistics, sys, time, urllib.error, urllib.request, uuid


def post(url, body, secret, timeout):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method='POST',
                                 headers={'Content-Type': 'application/json', 'X-Node-Secret': secret})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or 'null')


def run_once(urls, records, target, secret, timeout):
    prefix = uuid.uuid4().hex[:24]
    times, failed = [], 0
    order = urls[target:] + urls[:target]
    started = time.perf_counter()
    for i in range(records):
        key = f'{prefix}{i:040x}'
        body = {'recordKey': key, 'version': 1, 'recordHash': f'{i:064x}'}
        t0 = time.perf_counter()
        ok = False
        for url in order:
            try:
                if (post(url + '/node/records', body, secret, timeout) or {}).get('accepted'):
                    ok = True
                    break
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
        if ok:
            times.append((time.perf_counter() - t0) * 1000)
        else:
            failed += 1
    total = time.perf_counter() - started
    return times, failed, total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--urls', required=True, help='쉼표로 구분한 노드 주소')
    ap.add_argument('--records', type=int, default=300)
    ap.add_argument('--runs', type=int, default=3)
    ap.add_argument('--target', type=int, default=1)
    ap.add_argument('--secret', default='local-demo-change-me')
    ap.add_argument('--timeout', type=float, default=2.0, help='FairChain fairchain.nodes.timeout과 같은 값')
    ap.add_argument('--warmup', type=int, default=20, help='측정 전에 보내는 기록 수 (JIT 예열)')
    a = ap.parse_args()

    urls = [u.strip().rstrip('/') for u in a.urls.split(',') if u.strip()]
    if a.warmup:
        run_once(urls, a.warmup, a.target - 1, a.secret, a.timeout)

    rates, avgs = [], []
    for run in range(1, a.runs + 1):
        times, failed, total = run_once(urls, a.records, a.target - 1, a.secret, a.timeout)
        if not times:
            print(f'run {run}: 확정 0건 / {a.records}건')
            continue
        s = sorted(times)
        rate = len(times) / total
        rates.append(rate)
        avgs.append(statistics.mean(times))
        print(f'run {run}: 확정 {len(times)}/{a.records} (실패 {failed}) · {rate:.1f} 건/s · '
              f'평균 {statistics.mean(times):.1f} ms · 중간값 {statistics.median(times):.1f} ms · '
              f'95% {s[min(len(s) - 1, int(len(s) * 0.95))]:.1f} ms · 최대 {s[-1]:.1f} ms')
    if rates:
        print(f'평균: {statistics.mean(rates):.1f} 건/s · {statistics.mean(avgs):.1f} ms/건 ({len(rates)}회)')
    return 0 if rates else 1


if __name__ == '__main__':
    sys.exit(main())
