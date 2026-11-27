#!/usr/bin/env python3
"""이 PC 한 대에서 실패 없이 받을 수 있는 동시 진입 인원을 찾는다.

  caffeinate -i python3 loadtest/find-limit.py [시작=10000] [끝=16000]

1,000명씩 올리다가 처음 실패가 나오면, 통과한 인원과 실패한 인원 사이를 반씩 좁혀 100명 단위까지 찾는다.
측정마다 서버를 전부 껐다 켠다 (노드 장부 · DB · 대기열이 빈 상태에서 시작).
통과 기준: JMeter 요청 중 실패 0건. 결과 표는 loadtest/results/limit-search/summary.md, 측정별 원본은 loadtest/results/limitN-월일-시분초/.
"""
import collections, csv, json, os, pathlib, subprocess, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'loadtest' / 'results' / 'limit-search'
OUT.mkdir(parents=True, exist_ok=True)
SUMMARY = OUT / 'summary.md'
PROCS = 4
BISECT = '--bisect' in sys.argv      # --bisect 통과한인원 실패한인원: 1단계를 건너뛰고 그 사이만 찾는다
NUMS = [int(x) for x in sys.argv[1:] if x.isdigit()]
START = NUMS[0] if NUMS else 10000
END = NUMS[1] if len(NUMS) > 1 else 16000

def sh(cmd, env=None, log=None):
    e = dict(os.environ, **(env or {}))
    done = subprocess.run(cmd, shell=True, cwd=ROOT, env=e, capture_output=True, text=True, executable='/bin/zsh')
    if log:
        log.write_text(done.stdout + done.stderr)
    return done


def restart():
    for attempt in range(3):
        sh('./scripts/stop-local.sh')
        time.sleep(5)
        done = sh('./scripts/run-local.sh --mem --no-build', env={'EXAMPLE_LOAD_MEMBERS': '16000'})
        if done.returncode == 0 and operator_ready():
            return
    sys.exit('서버를 켜지 못했습니다:\n' + done.stdout[-2000:] + done.stderr[-2000:])


def operator_ready():
    body = json.dumps({'loginId': 'operator', 'password': 'operator1234'}).encode()
    for _ in range(60):
        try:
            req = urllib.request.Request('http://localhost:8080/api/operator/login', data=body, headers={'Content-Type': 'application/json'})
            if urllib.request.urlopen(req, timeout=5).status == 200:
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] if values else 0


def measure(n):
    restart()
    name = f'limit{n}'
    sh(f'./loadtest/run-multi.sh {name} {PROCS} {n // PROCS}', log=OUT / f'{name}.out',
       env={'VERIFY': '0', 'WAIT_MS': '120000', 'CONNECT_MS': '20000', 'TITLE': name})
    total = fail = bind = s503 = 0
    other = collections.Counter()
    enter, book, sold, t0, t1 = [], [], 0, None, 0
    run = sorted((ROOT / 'loadtest' / 'results').glob(name + '-*/all.jtl'))[-1]      # run-multi.sh는 실행마다 '이름-월일-시분초' 폴더를 만든다
    with open(run, encoding='utf8', errors='replace') as f:
        for r in csv.DictReader(f):
            total += 1
            ts, el = int(r['timeStamp']), int(r['elapsed'] or 0)
            t0 = ts if t0 is None else min(t0, ts)
            t1 = max(t1, ts + el)
            ok = r['success'] == 'true'
            if r['label'] == 'A-1 대기열 진입':
                enter.append(el)
            if r['label'] == 'A-3 예매':
                book.append(el)
                sold += ok
            if not ok:
                fail += 1
                if 'assign requested' in r['responseMessage']:
                    bind += 1
                elif r['responseCode'] == '503':
                    s503 += 1
                else:
                    other[(r['responseCode'][:30], r['responseMessage'][:40])] += 1
    terms = [int(line.split()[3]) for line in (OUT / f'{name}.out').read_text().splitlines()
             if line[:2] in ('A1', 'B1', 'C1') and line.split()[3].isdigit()]
    row = (f'| {n:,} | {"통과" if fail == 0 else "실패"} | {total:,} | {fail:,} | {bind:,} | {s503:,} | {sum(other.values()):,} '
           f'| {sum(enter) // max(1, len(enter)):,} | {pct(enter, 0.95):,} | {sum(book) // max(1, len(book)):,} | {sold} '
           f'| {(t1 - t0) / 1000:.0f} | {max(terms) if terms else "-"} | {time.strftime("%H:%M")} |')
    with SUMMARY.open('a') as f:
        f.write(row + '\n')
        if other:
            f.write(f'<!-- {n} 기타 실패: {other.most_common(5)} -->\n')
    print(row, flush=True)
    return fail == 0


if not SUMMARY.exists():
    SUMMARY.write_text('| 인원 | 판정 | 요청 수 | 실패 | 포트 부족 | 503 | 기타 | 진입 평균 ms | 진입 95% ms | 예매 평균 ms | 판매 | 전체 초 | Raft 임기 | 시각 |\n'
                       '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n')

last_ok, first_fail = (START, END) if BISECT else (None, None)
n = START
while n <= END and not BISECT:
    if measure(n):
        last_ok = n
        n += 1000
    else:
        first_fail = n
        break

if last_ok is None:
    print(f'결과: 시작 인원 {START:,}명에서 이미 실패', flush=True)
elif first_fail is None:
    print(f'결과: {END:,}명까지 실패 없음', flush=True)
else:
    # 통과한 인원과 실패한 인원의 가운데(100명 단위)를 재고, 결과에 따라 위쪽 · 아래쪽 절반으로 좁힌다
    while first_fail - last_ok > 100:
        m = (last_ok + first_fail) // 2 // 100 * 100
        if measure(m):
            last_ok = m
        else:
            first_fail = m
    print(f'결과: 실패 없이 통과한 최대 인원 {last_ok:,}명, 처음 실패한 인원 {first_fail:,}명', flush=True)
sh('./scripts/stop-local.sh')
