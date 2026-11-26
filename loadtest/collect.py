#!/usr/bin/env python3
"""부하 테스트 한 번의 결과를 한 폴더와 zip 하나로 모은다 — 그대로 Claude에게 보내 분석을 받을 수 있게.

  python3 loadtest/collect.py begin <결과 폴더> [설명]   JMeter 직전: 시작 시각 · 노드 상태 · PC · 커밋을 run.json에
  python3 loadtest/collect.py end <결과 폴더>            JMeter 직후: summary.md를 쓰고 <결과 폴더>.zip을 만든다

run-slow.sh · run-waves.sh · run-multi.sh 는 둘 다 스스로 부른다. jmeter를 손으로 돌릴 때는 앞뒤로 부른다:
  python3 loadtest/collect.py begin loadtest/results/run1 "설명"
  jmeter -n -t loadtest/fairticket.jmx -l loadtest/results/run1/all.jtl -j loadtest/results/run1/jmeter.log
  python3 loadtest/collect.py end loadtest/results/run1

summary.md: 단계별 건수 · 실패 · 응답 시간(평균 · 50/95/99% · 최대), 시간대별 처리량, 실패 원인, 노드 상태(전 → 후),
서버 로그 요약(경고 · 오류, 노드 상태 줄의 DB 연결 · 처리 중 요청 최댓값, 멈춤 의심). zip에는 그 시간의 서버 로그와
JMeter 원본(.jtl · jmeter.log)도 들어간다.
노드가 다른 PC에 있으면: NODES=http://IP:9001,http://IP:9002,http://IP:9003 (비밀값이 기본값과 다르면 NODE_SECRET=…).
그 PC의 노드 로그는 거기서 따로 모은다 (summary.md 끝에 그 명령이 시각까지 적혀 나온다).
"""
import csv, collections, datetime as dt, json, math, os, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'scripts'))
import collect_logs as cl  # noqa: E402

MARGIN = dt.timedelta(seconds=30)


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    return sorted_values[min(len(sorted_values) - 1, max(0, math.ceil(p / 100 * len(sorted_values)) - 1))]


def begin(folder, title):
    folder.mkdir(parents=True, exist_ok=True)
    targets, secret = cl.node_targets()
    now = dt.datetime.now()
    run = {'title': title, 'begin': now.isoformat(timespec='seconds'), 'machine': cl.machine(),
           'nodes_before': cl.node_status(targets, secret), 'node_urls': targets,
           'nodes_remote': bool(os.environ.get('NODES'))}
    (folder / 'run.json').write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'기록 시작 {now:%H:%M:%S} → {folder}')


def read_samples(folder):
    files = [folder / 'all.jtl'] if (folder / 'all.jtl').exists() else sorted(folder.glob('*.jtl'))
    rows = []
    for f in files:
        with open(f, encoding='utf-8', errors='replace', newline='') as fh:
            for r in csv.DictReader(fh):
                try:
                    rows.append((int(r['timeStamp']), int(r.get('elapsed') or 0), r.get('label', ''),
                                 r.get('success') == 'true', r.get('responseCode', ''), r.get('responseMessage', ''),
                                 r.get('failureMessage', '') or ''))
                except (KeyError, ValueError):
                    continue
    return files, rows


def jmeter_md(rows):
    md = []
    if not rows:
        return ['(JMeter 결과(.jtl)가 없다)'], None, None
    t0 = min(r[0] for r in rows)
    t1 = max(r[0] + r[1] for r in rows)
    seconds = max(1, (t1 - t0) / 1000)
    fails = sum(1 for r in rows if not r[3])
    md.append(f'- 요청 {len(rows):,}건 · 실패 {fails:,}건 ({fails / len(rows) * 100:.2f}%) · {seconds:.0f}초 · '
              f'평균 {len(rows) / seconds:.1f}건/초')
    enter = [r[0] for r in rows if r[2].startswith('A-1 대기열 진입')]
    if enter:
        md.append(f'- 대기열 진입 {len(enter):,}명이 {(max(enter) - min(enter)) / 1000:.1f}초 사이에 들어옴')

    md += ['', '### 단계별', '', '| 단계 | 건수 | 실패 | 실패율 | 평균 ms | 50% | 95% | 99% | 최대 |',
           '|---|---|---|---|---|---|---|---|---|']
    by_label = collections.OrderedDict()
    for r in sorted(rows):
        by_label.setdefault(r[2], []).append(r)
    for label, items in by_label.items():
        times = sorted(x[1] for x in items)
        bad = sum(1 for x in items if not x[3])
        md.append(f'| {label} | {len(items):,} | {bad:,} | {bad / len(items) * 100:.1f}% | {sum(times) // len(times):,} '
                  f'| {percentile(times, 50):,} | {percentile(times, 95):,} | {percentile(times, 99):,} | {times[-1]:,} |')

    step = max(5, math.ceil(seconds / 40))
    md += ['', f'### 시간대별 ({step}초 묶음, 0초 = 첫 요청)', '', '| 시각 | 요청/초 | 평균 ms | 95% ms | 실패 |', '|---|---|---|---|---|']
    buckets = collections.defaultdict(list)
    for r in rows:
        buckets[(r[0] - t0) // 1000 // step].append(r)
    for b in sorted(buckets):
        items = buckets[b]
        times = sorted(x[1] for x in items)
        at = dt.datetime.fromtimestamp((t0 + b * step * 1000) / 1000)
        md.append(f'| +{b * step}s ({at:%H:%M:%S}) | {len(items) / step:.1f} | {sum(times) // len(times):,} '
                  f'| {percentile(times, 95):,} | {sum(1 for x in items if not x[3]):,} |')

    reasons = collections.Counter((r[2], r[4], (r[5] or '')[:70], r[6][:90]) for r in rows if not r[3])
    if reasons:
        md += ['', '### 실패 원인 (많은 순)', '', '| 건수 | 단계 | 코드 | 메시지 | 판정 실패 내용 |', '|---|---|---|---|---|']
        for (label, code, message, failure), n in reasons.most_common(15):
            md.append(f'| {n:,} | {label} | {code} | {message.replace("|", "/")} | {failure.replace("|", "/")} |')
    return md, dt.datetime.fromtimestamp(t0 / 1000), dt.datetime.fromtimestamp(t1 / 1000)


def end(folder):
    if not folder.exists():
        sys.exit(f'폴더가 없다: {folder}')
    run_file = folder / 'run.json'
    run = json.loads(run_file.read_text(encoding='utf-8')) if run_file.exists() else {}
    files, rows = read_samples(folder)
    jm, first, last = jmeter_md(rows)
    now = dt.datetime.now()
    started = dt.datetime.fromisoformat(run['begin']) if run.get('begin') else (first or now)
    since, until = min(started, first or started) - MARGIN, now + MARGIN

    targets = run.get('node_urls')
    if targets is None:
        targets, _ = cl.node_targets()
    _, secret = cl.node_targets()
    after = cl.node_status(targets, secret)
    sliced = cl.slice_logs(since, until, folder / 'server-logs')
    info = run.get('machine') or cl.machine()

    md = [f'# 부하 테스트 결과: {folder.name}', '']
    if run.get('title'):
        md.append(f"- 실행: {run['title']}")
    md.append(f'- 시간: {started:%Y-%m-%d %H:%M:%S} ~ {now:%H:%M:%S} (이 PC 시각)')
    md += cl.machine_md(info)
    md += ['', '## JMeter', ''] + jm
    md += [''] + cl.node_table(run.get('nodes_before') or [], '## 노드 — 시작 전')
    md += [''] + cl.node_table(after, '## 노드 — 끝난 뒤')
    terms = [(b.get('id'), b.get('term'), a.get('term')) for b, a in zip(run.get('nodes_before') or [], after)
             if 'error' not in b and 'error' not in a and b.get('term') is not None and a.get('term') is not None]
    if terms and any(t1 != t0 for _, t0, t1 in terms):
        md.append('')
        md.append('- 임기가 바뀌었다 → 테스트 중에 리더 선거가 있었다: ' + ', '.join(f'{i} {t0}→{t1}' for i, t0, t1 in terms))
    heads = {(a.get('height'), a.get('hash')) for a in after if 'error' not in a}
    if len(heads) > 1:
        md.append('- 노드마다 높이 · 마지막 해시가 다르다 (모은 순간에 따라잡는 중이었을 수도 있다)')

    md += ['', '## 서버 로그 (이 PC, 시작 30초 전 ~ 끝 30초 뒤)', '']
    md += cl.findings(sliced) if sliced else ['(이 PC에 그 시간의 서버 로그가 없다)']
    node_logs_here = any(n.startswith('logs/nodes/') or n.startswith('raft-old/') for n in sliced)
    if run.get('nodes_remote') or not node_logs_here:
        md += ['', '노드가 다른 PC에 있으면 그 PC에서 같은 시간의 노드 로그를 모아 같이 보낸다:', '',
               '```', f'python scripts/collect_logs.py --from "{since:%Y-%m-%d %H:%M:%S}" --to "{until:%Y-%m-%d %H:%M:%S}"', '```']
    md += ['', '## 들어 있는 파일', '']
    md += [f'- {p.relative_to(folder).as_posix()} ({p.stat().st_size // 1024} KB)'
           for p in sorted(folder.rglob('*')) if p.is_file() and p.name != 'summary.md']
    (folder / 'summary.md').write_text('\n'.join(md) + '\n', encoding='utf-8')

    target = cl.zip_folder(folder, folder.parent / (folder.name + '.zip'))
    shown = '\n'.join(md)
    print(shown if len(shown) < 6000 else shown[:6000] + '\n…(나머지는 summary.md)')
    print(f'\n결과 묶음: {target} ({target.stat().st_size // 1024} KB)')
    print('이 zip 하나를 Claude에게 보내면 된다 (요약: summary.md, 원본: *.jtl, 서버 로그: server-logs/)')


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ('begin', 'end'):
        sys.exit(__doc__)
    folder = pathlib.Path(sys.argv[2])
    if sys.argv[1] == 'begin':
        begin(folder, ' '.join(sys.argv[3:]))
    else:
        end(folder)


if __name__ == '__main__':
    if os.name == 'nt':
        sys.stdout.reconfigure(encoding='utf-8')
    main()
