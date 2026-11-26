"""로그 모으기: 정해진 시간 동안의 로그만 잘라 zip 하나로 만든다 (Claude나 팀원에게 보내 원인을 찾을 때).

  python scripts/collect_logs.py                          최근 30분
  python scripts/collect_logs.py --minutes 90
  python scripts/collect_logs.py --from 20:05 --to 20:30  오늘 그 시각 (날짜까지: --from "2026-11-26 20:05")
  python scripts/collect_logs.py --nodes http://IP:9001,http://IP:9002,http://IP:9003 --secret 비밀값

모으는 것 (이 PC에 있는 것만):
  data/logs/nodes/   노드별 로그 · 접근 로그 · 멈춤 의심 때의 스레드 덤프 (scripts/raft_nodes.py가 남긴다)
  data/logs/         FairChain · 예제서버 로그 (scripts/run-local.sh · run-local.ps1이 남긴다)
  data/raft/*.log    예전 방식의 노드 로그 (새 로그 방식 전에 켠 노드)
+ 노드 상태 (역할 · 임기 · 리더 · 높이 · 해시), PC 정보, git 커밋, 경고 · 오류 요약 → data/logs/bundles/logs-<PC>-<시각>.zip

노드 주소는 --nodes · 환경 변수 NODES, 없으면 이 PC의 data/raft/layout.json. 비밀값은 --secret · NODE_SECRET · layout.json 순서.
loadtest/collect.py가 이 파일의 함수를 같이 쓴다.
"""
import argparse, collections, datetime as dt, json, os, pathlib, platform, re, shutil, socket, subprocess, sys, \
    urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOGS = ROOT / 'data' / 'logs'
OLD_NODE_LOGS = ROOT / 'data' / 'raft'
LAYOUT = ROOT / 'data' / 'raft' / 'layout.json'
SECRET_DEFAULT = 'local-demo-change-me'
STAMP = re.compile(r'^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})')


# ------------------------------------------------------------------ 시간

def parse_when(text, today=None):
    """'20:05', '20:05:30', '2026-11-26 20:05' → datetime (이 PC 시각)"""
    text = text.strip().replace('T', ' ')
    today = today or dt.date.today()
    for fmt in ('%Y-%m-%d %H:%M:%S', '%Y-%m-%d %H:%M'):
        try:
            return dt.datetime.strptime(text, fmt)
        except ValueError:
            pass
    for fmt in ('%H:%M:%S', '%H:%M'):
        try:
            return dt.datetime.combine(today, dt.datetime.strptime(text, fmt).time())
        except ValueError:
            pass
    sys.exit(f'시각을 읽지 못했다: {text} (예: 20:05 또는 "2026-11-26 20:05")')


def line_time(line):
    m = STAMP.match(line)
    if not m:
        return None
    try:
        return dt.datetime.strptime(m.group(1) + ' ' + m.group(2), '%Y-%m-%d %H:%M:%S')
    except ValueError:
        return None


# ------------------------------------------------------------------ 로그 자르기

def log_files():
    """모을 수 있는 로그 파일 (상대 이름, 경로)"""
    found = []
    if LOGS.exists():
        for p in sorted(LOGS.rglob('*')):
            if p.is_file() and 'bundles' not in p.relative_to(LOGS).parts and p.suffix in ('.log', '.txt'):
                found.append(('logs/' + p.relative_to(LOGS).as_posix(), p))
    if OLD_NODE_LOGS.exists():
        for p in sorted(OLD_NODE_LOGS.glob('*.log')):
            found.append(('raft-old/' + p.name, p))
    return found


def slice_logs(since, until, dest):
    """since~until 사이 줄만 dest 아래에 같은 이름으로 쓴다. 시각이 없는 줄(예외 스택)은 앞 줄을 따른다.
    스레드 덤프(.txt)는 파일 시각이 그 사이면 통째로. 반환: {상대 이름: [줄]}"""
    out = {}
    for name, path in log_files():
        modified = dt.datetime.fromtimestamp(path.stat().st_mtime)
        if modified < since:
            continue                    # 그 뒤로 쓴 적이 없다
        if path.suffix == '.txt':
            if modified <= until + dt.timedelta(minutes=1):
                lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
            else:
                continue
        else:
            lines, keep = [], False
            with open(path, encoding='utf-8', errors='replace') as f:
                for line in f:
                    t = line_time(line)
                    if t is not None:
                        keep = since <= t <= until
                    if keep:
                        lines.append(line.rstrip('\n'))
        if lines:
            target = dest / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('\n'.join(lines) + '\n', encoding='utf-8')
            out[name] = lines
    return out


# ------------------------------------------------------------------ 요약

NUMBERS = re.compile(r'0x[0-9a-f]+|\b[0-9a-f]{12,}\b|\d+')
HEALTH = re.compile(r'com\.fairchain\.node\.health\s*: (\S+) · 임기 (\S+)')
POOL = re.compile(r'DB 연결 (\d+)/(\d+) 사용 · 대기 (\d+)')
INFLIGHT = re.compile(r'처리 중 (\d+)')
FAILED = re.compile(r' · 실패 (\d+)')


def findings(sliced):
    """잘라 낸 로그에서 사람이 먼저 볼 것: 파일별 경고 · 오류 수, 자주 나온 메시지, 노드 상태 줄의 최댓값, 멈춤 의심"""
    md = []
    rows, messages = [], collections.Counter()
    health = {}
    for name, lines in sorted(sliced.items()):
        warn = err = 0
        for line in lines:
            level = ' ERROR ' if ' ERROR ' in line[:60] else ' WARN ' if ' WARN ' in line[:60] else None
            if level:
                if level == ' ERROR ':
                    err += 1
                else:
                    warn += 1
                text = line.split(' : ', 1)[1] if ' : ' in line else line[30:]
                messages[(pathlib.PurePosixPath(name).name, level.strip(), NUMBERS.sub('#', text)[:150])] += 1
            m = HEALTH.search(line)
            if m:
                node = pathlib.PurePosixPath(name).stem
                h = health.setdefault(node, {'lines': 0, 'pool_wait': 0, 'pool_used': 0, 'pool_size': 0, 'inflight': 0,
                                             'failed': 0, 'roles': [], 'stalls': 0})
                h['lines'] += 1
                role_term = f'{m.group(1)}/{m.group(2)}'
                if not h['roles'] or h['roles'][-1] != role_term:
                    h['roles'].append(role_term)
                p = POOL.search(line)
                if p:
                    h['pool_used'] = max(h['pool_used'], int(p.group(1)))
                    h['pool_size'] = max(h['pool_size'], int(p.group(2)))
                    h['pool_wait'] = max(h['pool_wait'], int(p.group(3)))
                i = INFLIGHT.search(line)
                if i:
                    h['inflight'] = max(h['inflight'], int(i.group(1)))
                f = FAILED.search(line)
                if f:
                    h['failed'] += int(f.group(1))
            if '멈춤 의심' in line:
                node = pathlib.PurePosixPath(name).stem
                health.setdefault(node, {'lines': 0, 'pool_wait': 0, 'pool_used': 0, 'pool_size': 0, 'inflight': 0,
                                         'failed': 0, 'roles': [], 'stalls': 0})['stalls'] += 1
        rows.append((name, len(lines), warn, err))

    md.append('| 파일 | 줄 | WARN | ERROR |')
    md.append('|---|---|---|---|')
    for name, n, w, e in rows:
        md.append(f'| {name} | {n} | {w} | {e} |')
    if health:
        md.append('')
        md.append('노드 상태 줄 (5초마다 한 줄) — 이 시간 동안의 최댓값:')
        md.append('')
        md.append('| 노드 | 상태 줄 | 역할/임기 변화 | DB 연결 최대 사용 | DB 연결 대기 최대 | 처리 중 요청 최대 | 실패 합계 | 멈춤 의심 |')
        md.append('|---|---|---|---|---|---|---|---|')
        for node, h in sorted(health.items()):
            roles = ' → '.join(h['roles'][:6]) + (' …' if len(h['roles']) > 6 else '')
            md.append(f"| {node} | {h['lines']} | {roles or '-'} | {h['pool_used']}/{h['pool_size']} | {h['pool_wait']} "
                      f"| {h['inflight']} | {h['failed']} | {h['stalls']} |")
    dumps = [n for n in sliced if n.endswith('.txt')]
    if dumps:
        md.append('')
        md.append('멈춤 의심 때 남은 스레드 덤프: ' + ', '.join(dumps))
    if messages:
        md.append('')
        md.append('자주 나온 경고 · 오류 (숫자는 #으로 바꿔 묶음):')
        md.append('')
        md.append('| 건수 | 파일 | 수준 | 메시지 |')
        md.append('|---|---|---|---|')
        for (name, level, text), n in messages.most_common(15):
            md.append(f'| {n} | {name} | {level} | {text.replace("|", "/")} |')
    return md


# ------------------------------------------------------------------ 노드 · PC

def node_targets(urls=None, secret=None):
    """(주소 목록, 비밀값)"""
    layout = None
    if LAYOUT.exists():
        try:
            layout = json.loads(LAYOUT.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            layout = None
    urls = urls or os.environ.get('NODES')
    if urls:
        targets = [u.strip().rstrip('/') for u in urls.split(',') if u.strip()]
    elif layout:
        targets = [n['url'] for n in layout.get('nodes', []) if n.get('role') == 'leader-layer']
    else:
        targets = []
    secret = secret or os.environ.get('NODE_SECRET') or (layout or {}).get('secret') or SECRET_DEFAULT
    return targets, secret


def node_status(urls, secret):
    rows = []
    for url in urls:
        req = urllib.request.Request(url + '/node/admin/status', headers={'X-Node-Secret': secret})
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                s = json.loads(r.read().decode())
            rows.append({'url': url, 'id': s.get('id'), 'role': s.get('raftRole'), 'term': s.get('term'),
                         'leader': s.get('leaderId'), 'height': s.get('height'), 'hash': (s.get('headHash') or '')[:12],
                         'out': s.get('outReason'), 'learners': s.get('learners'), 'excluded': s.get('excluded')})
        except Exception as e:      # 꺼짐 · 응답 없음도 그대로 기록한다
            rows.append({'url': url, 'error': type(e).__name__ + ': ' + str(e)[:80]})
    return rows


def node_table(rows, title=None):
    md = [title, ''] if title else []
    if not rows:
        return md + ['(노드 주소를 몰라 상태를 묻지 않았다 — NODES=http://IP:9001,… 로 준다)']
    md += ['| 노드 | 주소 | 역할 | 임기 | 리더 | 높이 | 마지막 해시 | 상태 |', '|---|---|---|---|---|---|---|---|']
    for r in rows:
        if 'error' in r:
            md.append(f"| ? | {r['url']} | 응답 없음 | | | | | {r['error']} |")
        else:
            md.append(f"| {r['id']} | {r['url']} | {r['role']} | {r['term']} | {r['leader'] or '-'} | {r['height']} "
                      f"| {r['hash']} | {r['out'] or 'OK'} |")
    return md


def _run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10, cwd=str(ROOT)).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ''


def machine():
    info = {'host': socket.gethostname(), 'os': platform.platform(), 'cpus': os.cpu_count(), 'python': platform.python_version()}
    if sys.platform == 'darwin':
        info['cpu'] = _run(['sysctl', '-n', 'machdep.cpu.brand_string'])
        mem = _run(['sysctl', '-n', 'hw.memsize'])
        info['memory_gb'] = round(int(mem) / 2 ** 30, 1) if mem.isdigit() else None
    elif os.name == 'nt':
        info['cpu'] = platform.processor()
        try:
            import ctypes

            class Mem(ctypes.Structure):
                _fields_ = [('len', ctypes.c_ulong), ('load', ctypes.c_ulong), ('total', ctypes.c_ulonglong),
                            ('avail', ctypes.c_ulonglong), ('tp', ctypes.c_ulonglong), ('ap', ctypes.c_ulonglong),
                            ('tv', ctypes.c_ulonglong), ('av', ctypes.c_ulonglong), ('ae', ctypes.c_ulonglong)]
            m = Mem()
            m.len = ctypes.sizeof(Mem)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
            info['memory_gb'] = round(m.total / 2 ** 30, 1)
        except Exception:
            info['memory_gb'] = None
    else:
        info['cpu'] = platform.processor()
        try:
            kb = int(next(l for l in open('/proc/meminfo') if l.startswith('MemTotal')).split()[1])
            info['memory_gb'] = round(kb / 2 ** 20, 1)
        except Exception:
            info['memory_gb'] = None
    changed = [l for l in _run(['git', 'status', '--porcelain', '--untracked-files=no']).splitlines() if l.strip()]
    info['git'] = {'branch': _run(['git', 'rev-parse', '--abbrev-ref', 'HEAD']), 'commit': _run(['git', 'rev-parse', '--short', 'HEAD']),
                   'changed_files': len(changed)}
    return info


def machine_md(info):
    g = info.get('git', {})
    return [f"- PC: {info['host']} · {info['os']} · CPU {info.get('cpu') or '?'} ({info['cpus']}코어) · 메모리 {info.get('memory_gb') or '?'}GB",
            f"- 코드: 브랜치 {g.get('branch') or '?'}, 커밋 {g.get('commit') or '?'}"
            + (f" (커밋하지 않은 수정 파일 {g['changed_files']}개)" if g.get('changed_files') else '')]


def zip_folder(folder, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(folder.rglob('*')):
            if p.is_file():
                z.write(p, folder.name + '/' + p.relative_to(folder).as_posix())
    return target


# ------------------------------------------------------------------ 명령

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--minutes', type=float, default=30, help='최근 몇 분 (--from이 없을 때)')
    ap.add_argument('--from', dest='since', help='시작 시각 (예: 20:05)')
    ap.add_argument('--to', dest='until', help='끝 시각 (없으면 지금)')
    ap.add_argument('--nodes', help='노드 주소 http://IP:9001,…')
    ap.add_argument('--secret', help='노드 공유 비밀값')
    a = ap.parse_args()
    now = dt.datetime.now()
    until = parse_when(a.until) if a.until else now
    since = parse_when(a.since) if a.since else until - dt.timedelta(minutes=a.minutes)
    info = machine()
    stamp = now.strftime('%Y%m%d-%H%M%S')
    folder = LOGS / 'bundles' / f"logs-{re.sub(r'[^A-Za-z0-9_-]', '_', info['host'])}-{stamp}"
    folder.mkdir(parents=True, exist_ok=True)
    sliced = slice_logs(since, until, folder)
    targets, secret = node_targets(a.nodes, a.secret)
    nodes = node_status(targets, secret)
    (folder / 'nodes.json').write_text(json.dumps(nodes, ensure_ascii=False, indent=2), encoding='utf-8')
    md = [f'# 로그 묶음 ({info["host"]})', '',
          f'- 시간: {since:%Y-%m-%d %H:%M:%S} ~ {until:%Y-%m-%d %H:%M:%S} (이 PC 시각)'] + machine_md(info) + ['']
    md += node_table(nodes, '## 노드 상태 (모은 시점)') + ['', '## 로그', '']
    md += findings(sliced) if sliced else ['(이 시간에 남은 로그가 없다 — data/logs 를 확인한다)']
    (folder / 'summary.md').write_text('\n'.join(md) + '\n', encoding='utf-8')
    target = zip_folder(folder, folder.with_suffix('.zip'))
    shutil.rmtree(folder, ignore_errors=True)
    print('\n'.join(md))
    print(f'\n묶음: {target} ({target.stat().st_size // 1024} KB) — 이 파일을 그대로 보내면 된다')


if __name__ == '__main__':
    if os.name == 'nt':
        sys.stdout.reconfigure(encoding='utf-8')
    main()
