"""노드가 답을 멈추는 순간을 잡아 원인을 남긴다 (읽기만 한다 — 노드 설정 · 장부는 건드리지 않는다).

  python scripts/node_stall_watch.py          켜 두면 1초마다 노드에 상태를 묻고, 2.5초 안에 답이 없으면 그 순간의
                                              스레드 덤프를 data/raft/stall-dumps/ 에 남기고 요약을 찍는다 (Ctrl+C로 끝)
  python scripts/node_stall_watch.py --once   지금 바로 한 번 덤프 · 요약 (status에 "꺼짐"이 보일 때)

왜 필요한가: status의 "꺼짐"은 "3초 안에 답이 없다"는 뜻일 뿐이라, 프로세스가 죽은 것인지 멈춘 것인지 알 수 없다.
노드 로그에는 요청이 남지 않아서, 멈춘 뒤에는 "누가 DB 연결을 쥐고 있었는지"를 알 길이 없다. 멈춘 순간에만 보인다.

요약에서 보는 것 (노드마다):
  검증 처리 중      FairChain의 검증 요청(verify · verifyBatch · proof · 원장 상태)을 처리하는 스레드 수
  DB 연결 대기      DB 연결(풀 10개)을 못 받아 기다리는 스레드 수
  장부 잠금 대기    DB 연결을 쥔 채 장부 잠금(Ledger.lock)을 기다리는 스레드 수
  블록 붙이기       블록을 붙이는 스레드(Raft-0 · ledger-catch-up)가 장부 잠금을 쥔 채 DB 연결을 기다리는지
"블록 붙이기 = 연결 대기"이고 "장부 잠금 대기"가 있으면 교착이다 (2026-11-25 A1이 30초씩 멈춘 원인).
"""
import argparse, json, pathlib, re, subprocess, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / 'data' / 'raft'
OUT = DATA / 'stall-dumps'
VERIFY = ('NodeService.batch', 'NodeService.verify', 'NodeService.proof', 'NodeService.ledgerStatus')


def load():
    layout = json.loads((DATA / 'layout.json').read_text(encoding='utf-8'))
    jcmd = pathlib.Path(layout['java']).with_name('jcmd.exe' if sys.platform == 'win32' else 'jcmd')
    if not jcmd.exists():
        sys.exit(f'jcmd가 없다: {jcmd} (JDK의 bin 폴더에 있어야 한다)')
    return layout, str(jcmd), [n for n in layout['nodes'] if n['role'] == 'leader-layer']


def answers(node, secret, timeout=2.5):
    """상태 조회가 시간 안에 오는지 — 이 조회도 DB 연결을 하나 쓰므로, 연결이 바닥나면 같이 멈춘다"""
    req = urllib.request.Request(node['url'] + '/node/admin/status', headers={'X-Node-Secret': secret})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            r.read()
            return True
    except Exception:
        return False


def thread_dump(node, jcmd):
    pid_file = DATA / f"{node['id']}.pid"
    if not pid_file.exists():
        return None
    r = subprocess.run([jcmd, pid_file.read_text().strip(), 'Thread.print'], capture_output=True, text=True,
                       errors='replace')
    return r.stdout if r.returncode == 0 and '"' in r.stdout else None


def summarize(text):
    """스레드 덤프 → 세어 본 값. 스레드마다 빈 줄로 나뉘고, 첫 줄이 "이름" 으로 시작한다"""
    threads = [b for b in text.split('\n\n') if b.startswith('"')]
    verify = [b for b in threads if any(k in b for k in VERIFY)]
    waiting_connection = [b for b in threads if 'HikariPool.getConnection' in b]
    # 장부 잠금: Ledger의 메서드 바로 아래에 찍힌 "- locked <주소>" / "- waiting to lock <주소>"
    held = set(re.findall(r'at com\.fairchain\.node\.ledger\.Ledger\.[^\n]*\n\s*- locked <(0x[0-9a-f]+)>', text))
    blocked = [b for b in threads
               if re.search(r'at com\.fairchain\.node\.ledger\.Ledger\.[^\n]*\n\s*- waiting to lock <0x[0-9a-f]+>', b)]
    appenders = [b for b in threads if 'Ledger.append' in b]
    stuck_appender = [re.match(r'"([^"]+)"', b).group(1) for b in appenders if 'HikariPool.getConnection' in b]
    return {
        'threads': len(threads),
        'http': sum(1 for b in threads if b.startswith('"http-nio-') and '-exec-' in b.split('"')[1]),
        'verify': len(verify),
        'waiting_connection': len(waiting_connection),
        'blocked_on_ledger_lock': len(blocked),
        'appender_waiting_connection': stuck_appender,
        'ledger_lock_held': bool(held),
    }


def capture(nodes, jcmd, tag):
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"{'노드':<4} {'스레드':>6} {'요청 스레드':>10} {'검증 처리 중':>11} {'DB 연결 대기':>11} {'장부 잠금 대기':>12}  블록 붙이기")
    for n in nodes:
        text = thread_dump(n, jcmd)
        if text is None:
            print(f"{n['id']:<4} 프로세스가 없다 (정말 꺼져 있다) — data/raft/{n['id']}.log 를 본다")
            continue
        (OUT / f"{n['id']}-{tag}.txt").write_text(text, encoding='utf-8')
        s = summarize(text)
        appender = ('연결 대기: ' + ', '.join(s['appender_waiting_connection'])) if s['appender_waiting_connection'] else '-'
        print(f"{n['id']:<4} {s['threads']:>6} {s['http']:>10} {s['verify']:>11} {s['waiting_connection']:>11} "
              f"{s['blocked_on_ledger_lock']:>12}  {appender}")
        if s['appender_waiting_connection'] and s['blocked_on_ledger_lock']:
            print(f"     → 교착: 블록을 붙이는 스레드가 장부 잠금을 쥔 채 DB 연결을 기다리고, "
                  f"DB 연결을 쥔 요청 {s['blocked_on_ledger_lock']}개가 그 잠금을 기다린다")
        elif s['waiting_connection'] >= 10:
            print('     → DB 연결이 바닥났다 (요청이 연결 10개보다 많이 몰림) — 덤프에서 연결을 쥔 스레드를 본다')
    print(f'덤프: {OUT}  (*-{tag}.txt)')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--once', action='store_true', help='지금 바로 한 번 덤프 · 요약하고 끝낸다')
    a = ap.parse_args()
    layout, jcmd, nodes = load()
    if a.once:
        capture(nodes, jcmd, time.strftime('%H%M%S'))
        return
    print(f"{', '.join(n['id'] for n in nodes)} 를 지켜본다 — 답이 2.5초 넘게 없으면 덤프를 뜬다 (Ctrl+C로 끝)", flush=True)
    try:
        while True:
            for n in nodes:
                if not answers(n, layout['secret']):
                    stamp = time.strftime('%H%M%S')
                    print(f"\n{time.strftime('%H:%M:%S')} {n['id']} 가 답하지 않는다", flush=True)
                    capture(nodes, jcmd, stamp + '-1')
                    time.sleep(3)
                    capture(nodes, jcmd, stamp + '-2')      # 3초 뒤에도 같은 모습이면 잠깐 느린 것이 아니라 멈춘 것
                    sys.stdout.flush()
                    time.sleep(35)                           # 같은 멈춤을 되풀이해 뜨지 않게 (연결 대기는 30초에 풀린다)
                    break
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    if sys.platform == 'win32':
        sys.stdout.reconfigure(encoding='utf-8')
    main()
