"""리더 층 Raft 노드 실행 · 시연 도구 (Windows · macOS 공통, Python 3.9+).

PC 1대에 시연 구성 전체(프로세스 9개)를 띄우거나, PC 1대 = 팀 1개로 띄운다 (11/12 답변: "PC 1대 = 팀(기관) 1개").

  python scripts/raft_nodes.py start [--fresh] [--patrol]     PC 1대: 리더 층 A1 · B1 · C1(9001~9003) + 팀원 6대
  python scripts/raft_nodes.py start --team A --host 192.168.0.11 \\
         --leaders A1=http://192.168.0.11:9001,B1=http://192.168.0.12:9001,C1=http://192.168.0.13:9001
                                                             PC 1대 = 팀 A: A1(9001) + 팀원 A2(9011) · A3(9012)
  python scripts/raft_nodes.py status                         노드별 역할 · 임기 · 높이 · 빠진 이유 · 팀원 · 판정 기록
  python scripts/raft_nodes.py stop [ID]                      전부 / 한 노드 끄기 (장부는 남김)
  python scripts/raft_nodes.py up ID                          한 노드 다시 켜기
  python scripts/raft_nodes.py write [N]                      기록 N건 보내기 (FairChain 대신)
  python scripts/raft_nodes.py kill-leader                    리더를 강제 종료하고 다음 기록이 확정될 때까지 시간 재기
  python scripts/raft_nodes.py tamper ID --block K [--rewrite] 꺼서 H2를 고치고 다시 켬 (--rewrite: 뒤 해시까지 다시 계산)
  python scripts/raft_nodes.py report ID                      "이 노드가 다르다" 신고 (FairChain이 하는 신고와 같음)
  python scripts/raft_nodes.py join D1 --team D --port 9004   새 팀 D의 노드를 켜고 노드 관리 API로 가입 (learner → 승격)
  python scripts/raft_nodes.py new-node D1 --team D --host 192.168.0.14 --leaders A1=http://192.168.0.11:9001,B1=...,C1=...
                                                             다른 PC에서: 새 팀의 노드 프로세스만 켠다 (--members N으로 팀원도).
                                                             가입은 FairChain 점검 화면의 "노드 추가"에 주소를 적거나 add로
  python scripts/raft_nodes.py add http://192.168.0.14:9001   주소만으로 가입 요청 (리더가 그 노드를 확인 → learner → 투표 멤버)
  python scripts/raft_nodes.py leave D1                       해지 (팀 3개 미만이 되면 거부). 해지된 ID는 다시 쓰지 않는다 —
                                                             그 팀을 다시 넣을 때는 새 ID로: new-node D1b --team D --port 9005
  python scripts/raft_nodes.py resume                         기록 중단 상태를 관리자가 풀기
  python scripts/raft_nodes.py scenario                       시연 순서 전체를 자동으로 돌려 확인 (빈 장부에서 시작)
  python scripts/raft_nodes.py failover --times 10            (PC 1대) 리더 끄기를 되풀이해 새 리더까지 시간 측정
  python scripts/raft_nodes.py watch-failover                 (PC 3대) 리더가 있는 PC에서 리더를 끄면 새 리더까지 측정

노드는 data/raft/<ID> (H2) · <ID>-raft.sqlite (Raft 상태) · <ID>.key (개인키) · <ID>.log 를 쓴다.
Java는 D:\\Java\\bin\\java.exe → JAVA_HOME → PATH 순서로 찾는다 (--java로 지정 가능).
"""
import argparse, glob, hashlib, json, os, pathlib, shutil, signal, subprocess, sys, tempfile, time, urllib.error, \
    urllib.request, uuid, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / 'data' / 'raft'
LAYOUT = DATA / 'layout.json'
SECRET_DEFAULT = 'local-demo-change-me'


# ------------------------------------------------------------------ 공용

def java_exe(given=None):
    if given:
        return given
    if os.name == 'nt' and os.path.exists(r'D:\Java\bin\java.exe'):
        return r'D:\Java\bin\java.exe'    # PATH의 java는 Oracle 대리 실행기라 끝난 뒤에도 JVM이 남는다
    home = os.environ.get('JAVA_HOME')
    if home:
        return str(pathlib.Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    return 'java'


def node_jar():
    found = [j for j in glob.glob(str(ROOT / 'node' / 'build' / 'libs' / 'node-*.jar')) if not j.endswith('-plain.jar')]
    if not found:
        sys.exit('node jar가 없다. 먼저: gradlew :node:bootJar')
    return found[0]


def call(method, url, body=None, secret=SECRET_DEFAULT, timeout=5):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={'Content-Type': 'application/json', 'X-Node-Secret': secret})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None
    except (urllib.error.URLError, TimeoutError, OSError):
        return 0, None


def h(*fields):
    """Sha256RecordHasher와 같은 방식: 필드마다 "길이:값" """
    return hashlib.sha256(''.join(f'{len(str(f))}:{f}' for f in fields).encode()).hexdigest()


def load_layout():
    if not LAYOUT.exists():
        sys.exit('켜진 구성이 없다. 먼저: python scripts/raft_nodes.py start')
    return json.loads(LAYOUT.read_text(encoding='utf-8'))


def save_layout(layout):
    DATA.mkdir(parents=True, exist_ok=True)
    LAYOUT.write_text(json.dumps(layout, indent=2, ensure_ascii=False), encoding='utf-8')


def port_free(port):
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.3)
        return s.connect_ex(('127.0.0.1', port)) != 0


def member_ports(first, count):
    """팀원 포트: first부터 차례로, 다른 프로그램이 쓰는 포트는 건너뛴다 (예: ASUS Armoury가 9012를 씀)"""
    ports, port = [], first
    while len(ports) < count and port < first + 9:
        if port_free(port):
            ports.append(port)
        port += 1
    if len(ports) < count:
        sys.exit(f'{first}~{first + 8}에서 빈 포트를 찾지 못했다')
    return ports


def build_layout(a):
    secret = a.secret
    if a.team:
        if not a.leaders:
            sys.exit('--team 모드는 --leaders A1=http://ip:9001,B1=...,C1=... 가 필요하다')
        leaders = a.leaders
        lid = next((p.split('=')[0] for p in leaders.split(',') if p.split('=')[0].rstrip('0123456789') == a.team), None)
        if lid is None:
            sys.exit(f'--leaders에 팀 {a.team}의 노드가 없다')
        nodes = [{'id': lid, 'port': 9001, 'role': 'leader-layer', 'url': f'http://{a.host}:9001'}]
        for k, port in enumerate(member_ports(9011, a.members)):
            nodes.append({'id': f'{a.team}{k + 2}', 'port': port, 'role': 'member', 'url': f'http://{a.host}:{port}'})
    else:
        # --host에 이 PC의 IP를 주면 노드가 그 주소로 서로를 부른다 — 나중에 다른 PC의 노드를 붙이려면 필요하다
        # (localhost로 시작한 그룹에는 다른 PC의 노드가 접속할 수 없다)
        host = a.host or 'localhost'
        leaders = ','.join(f'{team}1=http://{host}:{9001 + t}' for t, team in enumerate('ABC'))
        nodes = []
        for t, team in enumerate('ABC'):
            nodes.append({'id': f'{team}1', 'port': 9001 + t, 'role': 'leader-layer', 'url': f'http://{host}:{9001 + t}'})
        for t, team in enumerate('ABC'):
            for k, port in enumerate(member_ports(9011 + 10 * t, a.members)):
                nodes.append({'id': f'{team}{k + 2}', 'port': port, 'role': 'member', 'url': f'http://{host}:{port}'})
    return {'leaders': leaders, 'secret': secret, 'patrol': a.patrol, 'java': java_exe(a.java), 'nodes': nodes,
            'heartbeat_timeout': getattr(a, 'heartbeat_timeout', None)}


def node_of(layout, node_id):
    n = next((x for x in layout['nodes'] if x['id'] == node_id), None)
    if n is None:
        sys.exit(f'{node_id}는 이 구성에 없다: ' + ', '.join(x['id'] for x in layout['nodes']))
    return n


def leader_layer(layout):
    return [n for n in layout['nodes'] if n['role'] == 'leader-layer']


def is_up(n, layout):
    return call('GET', n['url'] + '/node/ledger/status', secret=layout['secret'], timeout=2)[0] == 200


def launch(n, layout):
    DATA.mkdir(parents=True, exist_ok=True)
    jar = DATA / 'node.jar'
    if not jar.exists():
        shutil.copy(node_jar(), jar)     # 다시 빌드해도 돌고 있는 노드의 jar는 그대로
    db = DATA / n['id']
    env = {**os.environ, 'NODE_PORT': str(n['port']), 'NODE_ID': n['id'], 'NODE_ROLE': n['role'],
           'NODE_MEMBERS': layout['leaders'], 'NODE_URL': n['url'], 'NODE_SECRET': layout['secret'],
           'NODE_DB': str(db), 'NODE_KEY_FILE': str(DATA / f"{n['id']}.key"),
           'NODE_RAFT_STORE': str(DATA / f"{n['id']}-raft.sqlite"),
           'NODE_PATROL': 'true' if layout.get('patrol') else 'false'}
    if layout.get('heartbeat_timeout'):
        env['NODE_RAFT_HEARTBEAT_TIMEOUT_SECS'] = str(layout['heartbeat_timeout'])
    if n.get('team'):
        env['NODE_TEAM'] = n['team']
    if n.get('team_leader'):
        env['NODE_TEAM_LEADER'] = n['team_leader']     # 처음 명단에 없는(나중에 가입하는) 팀의 팀원
    log = open(DATA / f"{n['id']}.log", 'ab')
    kwargs = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS} if os.name == 'nt' \
        else {'start_new_session': True}
    p = subprocess.Popen([layout['java'], '-Xmx256m', '-jar', str(jar), '--logging.level.root=WARN',
                          '--logging.level.com.fairchain=INFO', '--logging.level.io.microraft=INFO'],
                         cwd=str(ROOT), env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **kwargs)
    (DATA / f"{n['id']}.pid").write_text(str(p.pid))


def kill(n):
    pid_file = DATA / f"{n['id']}.pid"
    if not pid_file.exists():
        return False
    pid = int(pid_file.read_text())
    pid_file.unlink()
    if os.name == 'nt':
        r = subprocess.run(['taskkill', '/F', '/T', '/PID', str(pid)], capture_output=True, text=True)
        if r.returncode != 0:
            print(f"  ! {n['id']} (PID {pid}) 끄기 실패: {(r.stderr or r.stdout).strip()[:120]}")
            return False
    else:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            return False
    return True


def wait_up(nodes, layout, seconds=120):
    until = time.time() + seconds
    pending = list(nodes)
    while pending and time.time() < until:
        pending = [n for n in pending if not is_up(n, layout)]
        time.sleep(0.5)
    for n in pending:
        print(f"  {n['id']} 이(가) {seconds}초 안에 켜지지 않았다 — {DATA / (n['id'] + '.log')}")


def wait_down(n, layout, seconds=15):
    until = time.time() + seconds
    while time.time() < until and is_up(n, layout):
        time.sleep(0.3)


def statuses(layout):
    out = {}
    for n in layout['nodes']:
        s1, ledger = call('GET', n['url'] + '/node/ledger/status', secret=layout['secret'], timeout=3)
        s2, st = call('GET', n['url'] + '/node/admin/status', secret=layout['secret'], timeout=3)
        out[n['id']] = (ledger if s1 == 200 else None, st if s2 == 200 else None)
    return out


def find_leader(layout, skip=None):
    """skip: 방금 끈 노드 — Windows에서는 닫힌 포트로의 연결이 바로 실패하지 않고 약 2초 걸려 측정을 늘린다"""
    for n in leader_layer(layout):
        if skip and n['id'] == skip:
            continue
        code, st = call('GET', n['url'] + '/node/admin/status', secret=layout['secret'], timeout=2 if not skip else 0.5)
        if code == 200 and st and st.get('raftRole') == 'LEADER':
            return n
    return None


def write_one(layout, ticket_key, skip=None):
    body = {'recordKey': ticket_key, 'version': 1, 'recordHash': hashlib.sha256(ticket_key.encode()).hexdigest()}
    for n in leader_layer(layout):
        if skip and n['id'] == skip:
            continue
        code, r = call('POST', n['url'] + '/node/records', body, layout['secret'], timeout=2.5)
        if code == 200 and r and r.get('accepted'):
            return r['blockIndex']
    return None


def write_many(layout, count, retry_seconds=15):
    last = None
    for _ in range(count):
        key = uuid.uuid4().hex + uuid.uuid4().hex
        until = time.time() + retry_seconds
        while (last := write_one(layout, key)) is None and time.time() < until:
            time.sleep(0.5)        # FairChain 대기 줄처럼 0.5초 뒤 다시
        if last is None:
            sys.exit('기록이 확정되지 않는다 — status로 노드 상태를 확인')
    return last


# ------------------------------------------------------------------ H2 직접 수정 (시연용 변조)

def h2_jar():
    target = DATA / 'h2.jar'
    if not target.exists():
        with zipfile.ZipFile(DATA / 'node.jar' if (DATA / 'node.jar').exists() else node_jar()) as z:
            name = next(e for e in z.namelist() if e.startswith('BOOT-INF/lib/h2-'))
            target.write_bytes(z.read(name))
    return target


def h2_sql(layout, node_id, sql):
    url = f"jdbc:h2:file:{DATA / node_id}"
    r = None
    for user in ('', 'sa'):         # 노드 설정에 사용자 이름이 없어 H2 파일은 빈 사용자 이름으로 만들어진다
        r = subprocess.run([layout['java'], '-cp', str(h2_jar()), 'org.h2.tools.Shell', '-url', url, '-user', user,
                            '-password', '', '-sql', sql], capture_output=True, text=True)
        if r.returncode == 0:
            return r.stdout
    sys.exit(f'H2 수정 실패: {(r.stderr or r.stdout)[:500]}')


def tamper(layout, node_id, block, rewrite):
    """--rewrite가 없으면 recordHash만 바꾼다(해시 검사에 걸림). 있으면 그 뒤 해시까지 다시 계산한다(서명으로만 잡힘)"""
    forged = 'f' * 64
    if not rewrite:
        h2_sql(layout, node_id, f"UPDATE BLOCK SET RECORD_HASH='{forged}' WHERE BLOCK_INDEX={block}")
        return
    csv = pathlib.Path(tempfile.mkdtemp()) / 'blocks.csv'
    h2_sql(layout, node_id, f"CALL CSVWRITE('{csv.as_posix()}', 'SELECT BLOCK_INDEX, PREV_BLOCK_HASH, RECORD_KEY, VERSION, "
                            f"RECORD_HASH FROM BLOCK WHERE BLOCK_INDEX >= {block} ORDER BY BLOCK_INDEX')")
    rows = [line.replace('"', '').split(',') for line in csv.read_text().splitlines()[1:] if line.strip()]
    prev = rows[0][1]
    stmts = []
    for index, _, key, version, record_hash in rows:
        if int(index) == block:
            record_hash = forged
        new_hash = h(index, prev, key, version, record_hash)
        stmts.append(f"UPDATE BLOCK SET PREV_BLOCK_HASH='{prev}', RECORD_HASH='{record_hash}', BLOCK_HASH='{new_hash}' "
                     f"WHERE BLOCK_INDEX={index}")
        prev = new_hash
    h2_sql(layout, node_id, '; '.join(stmts))
    shutil.rmtree(csv.parent, ignore_errors=True)


# ------------------------------------------------------------------ 명령

def cmd_start(a):
    if LAYOUT.exists() and not a.fresh:
        layout = load_layout()
        print('이전 구성으로 다시 켠다 (장부 이어서). 새로 시작하려면 --fresh')
    else:
        if a.fresh and DATA.exists():
            for n in json.loads(LAYOUT.read_text(encoding='utf-8'))['nodes'] if LAYOUT.exists() else []:
                kill(n)
            time.sleep(1)
            shutil.rmtree(DATA, ignore_errors=True)
        layout = build_layout(a)
        DATA.mkdir(parents=True, exist_ok=True)
        shutil.copy(node_jar(), DATA / 'node.jar')
        save_layout(layout)
    for n in layout['nodes']:
        if not is_up(n, layout):
            launch(n, layout)
    print(f"노드 {len(layout['nodes'])}대 켜는 중...")
    wait_up(layout['nodes'], layout)
    cmd_status(a)


def cmd_stop(a):
    layout = load_layout()
    targets = [node_of(layout, a.id)] if a.id else layout['nodes']
    for n in targets:
        print(f"{n['id']} 끔" if kill(n) else f"{n['id']}는 이미 꺼져 있다")


def cmd_up(a):
    layout = load_layout()
    n = node_of(layout, a.id)
    if not is_up(n, layout):
        launch(n, layout)
    wait_up([n], layout)
    print(f"{n['id']} 켜짐 ({n['url']})")


def cmd_status(a):
    layout = load_layout()
    rows = statuses(layout)
    print(f"{'ID':<4} {'역할':<12} {'Raft':<9} {'임기':>4} {'리더':<5} {'높이':>6} {'마지막 해시':<14} {'체인':<4}  상태")
    verdicts = []
    for n in layout['nodes']:
        ledger, st = rows[n['id']]
        if ledger is None:
            print(f"{n['id']:<4} {n['role']:<12} {'꺼짐'}")
            continue
        st = st or {}
        state = st.get('outReason') or ('기록 중단' if st.get('halted') else 'OK')
        team = ' · 팀원 ' + ', '.join(f"{m['id']}:{m['state']}@{m['lastBlockIndex']}" for m in st.get('teamMembers') or []) \
            if st.get('teamMembers') else ''
        print(f"{n['id']:<4} {n['role']:<12} {st.get('raftRole', '-'):<9} {str(st.get('term') or '-'):>4} "
              f"{str(st.get('leaderId') or '-'):<5} {ledger['height']:>6} {ledger['headHash'][:12]:<14} "
              f"{'정상' if ledger['chainValid'] else '깨짐':<4}  {state}{team}")
        if st.get('verdicts') and not verdicts:
            verdicts = st['verdicts']
    if verdicts:
        print('판정 기록: ' + ' → '.join(f"{v.get('suspectId') or ''} #{v.get('blockIndex') or '-'} {v['action']}"
                                      + (f" {v['differentFields']}" if v.get('differentFields') else '') for v in verdicts))


def cmd_write(a):
    layout = load_layout()
    t0 = time.time()
    last = write_many(layout, a.count)
    print(f"{a.count}건 확정 ({time.time() - t0:.2f}초) — 마지막 블록 #{last}")


def cmd_kill_leader(a):
    layout = load_layout()
    leader = find_leader(layout)
    if leader is None:
        sys.exit('리더가 없다')
    t0 = time.time()
    kill(leader)
    print(f"리더 {leader['id']} 강제 종료 ({time.strftime('%H:%M:%S', time.localtime(t0))}.{int(t0 % 1 * 1000):03d}, "
          f"종료 명령 {time.time() - t0:.2f}초)")
    new = None
    while new is None:       # 0.1초마다 남은 노드의 상태만 본다
        time.sleep(0.1)
        new = find_leader(layout, skip=leader['id'])
        if time.time() - t0 > 30:
            sys.exit('30초 안에 새 리더가 뽑히지 않았다')
    elected = time.time() - t0
    key = uuid.uuid4().hex + uuid.uuid4().hex
    while write_one(layout, key, skip=leader['id']) is None:
        time.sleep(0.5)
        if time.time() - t0 > 30:
            sys.exit('30초 안에 새 리더가 기록을 받지 않았다')
    print(f"새 리더 {new['id']}까지 {elected:.2f}초 · 다음 기록 확정까지 {time.time() - t0:.2f}초")
    print(f"예전 리더를 다시 켜려면: python scripts/raft_nodes.py up {leader['id']}")


def cmd_failover(a):
    """리더 끄기 → 새 리더 → 예전 리더 다시 켜기를 되풀이해 새 리더까지의 시간을 모은다 (9단계 측정용)"""
    layout = load_layout()
    down = [n for n in leader_layer(layout) if not is_up(n, layout)]
    for n in down:      # 리더를 끄려면 나머지 둘이 켜져 있어야 과반이 남는다
        print(f"  {n['id']}가 꺼져 있어 먼저 켠다")
        launch(n, layout)
    wait_up(down, layout)
    elected, confirmed = [], []
    for round_ in range(a.times):
        write_many(layout, 3)
        leader = find_leader(layout)
        kill(leader)
        t0 = time.time()
        new = None
        while new is None:
            time.sleep(0.1)
            new = find_leader(layout, skip=leader['id'])
            if time.time() - t0 > 30:
                sys.exit('30초 안에 새 리더가 뽑히지 않았다 — status로 확인')
        elected.append(time.time() - t0)
        key = uuid.uuid4().hex + uuid.uuid4().hex
        while write_one(layout, key, skip=leader['id']) is None:
            time.sleep(0.5)
            if time.time() - t0 > 30:
                sys.exit('30초 안에 기록이 확정되지 않았다')
        confirmed.append(time.time() - t0)
        print(f"  {round_ + 1}회: 리더 {leader['id']} 끔 → 새 리더 {new['id']} {elected[-1]:.2f}초 · 다음 기록 {confirmed[-1]:.2f}초")
        launch(leader, layout)
        wait_up([leader], layout)
        # 막 켠 노드는 하트비트 시간 제한 동안 다른 노드의 사전 투표를 거절한다 (리더가 살아 있다고 봄) — 다음 측정에 섞이지
        # 않게, 팔로워로 자리 잡은 뒤 시간 제한만큼 더 기다린다
        until = time.time() + 60
        while time.time() < until:
            code, st = call('GET', leader['url'] + '/node/admin/status', secret=layout['secret'], timeout=2)
            if code == 200 and st and st.get('raftRole') == 'FOLLOWER' and st.get('leaderId'):
                break
            time.sleep(0.3)
        # 막 켠 노드가 섞이면 (켠 지 10초 안) 새 리더까지 8초가 걸리는 경우가 있었다 — 측정 사이를 넉넉히 둔다
        time.sleep(15)
    print(f"새 리더까지: 평균 {sum(elected) / len(elected):.2f}초 · 최대 {max(elected):.2f}초 · "
          f"다음 기록 확정까지: 평균 {sum(confirmed) / len(confirmed):.2f}초 · 최대 {max(confirmed):.2f}초")


def cmd_watch_failover(a):
    """PC 3대 측정용: 리더가 있는 PC에서 리더를 끄면(Ctrl+C · stop), 이 PC에서 "리더 응답 끊김 → 새 리더"까지 잰다"""
    layout = load_layout()
    leaders = [{'id': p.split('=')[0], 'url': p.split('=', 1)[1]} for p in layout['leaders'].split(',')]
    def leader_now():
        for n in leaders:
            code, st = call('GET', n['url'] + '/node/admin/status', secret=layout['secret'], timeout=1)
            if code == 200 and st and st.get('raftRole') == 'LEADER':
                return n
        return None
    old = leader_now()
    if old is None:
        sys.exit('리더가 없다')
    print(f"리더는 {old['id']} ({old['url']}) — 그 PC에서 리더를 끄세요 (Ctrl+C 또는 raft_nodes.py stop {old['id']})")
    while call('GET', old['url'] + '/node/ledger/status', secret=layout['secret'], timeout=1)[0] == 200:
        time.sleep(0.05)
    t0 = time.time()
    print(f"{old['id']} 응답 끊김 ({time.strftime('%H:%M:%S')}) — 새 리더를 기다림")
    while True:
        new = leader_now()
        if new and new['id'] != old['id']:
            break
        time.sleep(0.1)
        if time.time() - t0 > 60:
            sys.exit('60초 안에 새 리더가 없다')
    elected = time.time() - t0
    key = uuid.uuid4().hex + uuid.uuid4().hex
    body = {'recordKey': key, 'version': 1, 'recordHash': hashlib.sha256(key.encode()).hexdigest()}
    def accepted(n):
        r = call('POST', n['url'] + '/node/records', body, layout['secret'], 2.5)[1]
        return bool(r and r.get('accepted'))
    while not any(accepted(n) for n in leaders if n['id'] != old['id']):
        time.sleep(0.5)
    print(f"새 리더 {new['id']}까지 {elected:.2f}초 · 다음 기록 확정까지 {time.time() - t0:.2f}초")


def cmd_tamper(a):
    layout = load_layout()
    n = node_of(layout, a.id)
    was_up = is_up(n, layout)
    if was_up:
        kill(n)
        wait_down(n, layout)
        time.sleep(1)       # H2 파일 잠금이 풀릴 때까지
    tamper(layout, n['id'], a.block, a.rewrite)
    print(f"{n['id']}의 블록 #{a.block}을 고쳤다" + (' (뒤 해시까지 다시 계산 — 서명으로만 잡힌다)' if a.rewrite else ''))
    if was_up and not a.keep_off:
        launch(n, layout)
        wait_up([n], layout)
        print(f"{n['id']} 다시 켬 — 켤 때 검사 · 신고 · 판정 · 복구는 status로 지켜본다")


def cmd_report(a):
    layout = load_layout()
    for n in leader_layer(layout):
        code, ack = call('POST', n['url'] + '/node/consensus/reports',
                         {'suspectId': a.id, 'reporter': 'operator'}, layout['secret'], timeout=15)
        if code == 200 and ack and ack.get('accepted'):
            v = ack.get('verdict')
            print(f"판정: {ack['reason']}" + (f" — 블록 #{v['blockIndex']} {v['action']} {v['differentFields']}" if v else ''))
            return
    print('신고를 받아 준 노드가 없다')


def layout_host(layout):
    """이 구성의 노드들이 쓰는 호스트 (localhost 또는 start --host로 준 IP)"""
    first = layout['nodes'][0]['url'] if layout['nodes'] else layout['leaders'].split(',')[0].split('=', 1)[1]
    return first.split('://', 1)[1].rsplit(':', 1)[0]


def cmd_new_node(a):
    """새 팀의 노드 프로세스만 켠다 (다른 PC에서도). 리더 층 가입은 FairChain 점검 화면의 "노드 추가"나 add 명령으로 한다"""
    if LAYOUT.exists():
        layout = load_layout()
        if a.leaders and a.leaders != layout['leaders']:
            sys.exit('이 PC에는 다른 --leaders로 만든 구성이 있다. 그 구성을 쓰려면 --leaders를 빼고, 새로 하려면 data/raft를 지운다')
    else:
        if not a.leaders:
            sys.exit('이 PC에 구성이 없다 — 붙을 그룹의 처음 멤버를 알려 준다: --leaders A1=http://ip:9001,B1=...,C1=...')
        layout = {'leaders': a.leaders, 'secret': a.secret, 'patrol': False, 'java': java_exe(a.java), 'nodes': [],
                  'heartbeat_timeout': None}
        DATA.mkdir(parents=True, exist_ok=True)
        shutil.copy(node_jar(), DATA / 'node.jar')
    host = a.host or (layout_host(layout) if layout['nodes'] else 'localhost')
    if host == 'localhost' and 'localhost' not in layout['leaders']:
        sys.exit('다른 PC의 그룹에 붙으려면 --host에 이 PC의 IP를 준다 (다른 노드가 이 노드에 접속할 주소)')
    added = []
    if not any(n['id'] == a.id for n in layout['nodes']):
        added.append({'id': a.id, 'port': a.port, 'role': 'leader-layer', 'url': f'http://{host}:{a.port}', 'team': a.team})
    first = 9011 + 10 * (a.port - 9001) if 9001 <= a.port <= 9008 else a.port + 10
    have = sum(1 for n in layout['nodes'] if n.get('team_leader') == a.id)
    for k, port in enumerate(member_ports(first, max(0, a.members - have))):
        added.append({'id': f'{a.team}{have + k + 2}', 'port': port, 'role': 'member', 'url': f'http://{host}:{port}',
                      'team': a.team, 'team_leader': a.id})
    layout['nodes'].extend(added)
    save_layout(layout)
    mine = [n for n in layout['nodes'] if n['id'] == a.id or n.get('team_leader') == a.id]
    for n in mine:
        if not is_up(n, layout):
            launch(n, layout)
    wait_up(mine, layout)
    url = node_of(layout, a.id)['url']
    print(f"{a.id} (팀 {a.team}) 켜짐: {url}" + (f" · 팀원 {len(mine) - 1}대" if len(mine) > 1 else ''))
    print('아직 리더 층 멤버가 아니다 (learner로 기다림). 가입시키려면:')
    print(f"  FairChain 점검 화면 → 블록체인 노드 → 노드 추가에  {url}")
    print(f"  또는: python scripts/raft_nodes.py add {url}   (기존 그룹이 있는 PC에서)")


def cmd_add(a):
    """주소만으로 가입 요청 — FairChain 점검 화면의 "노드 추가"와 같다. 리더가 그 주소의 노드를 확인한 뒤 learner → 투표 멤버"""
    layout = load_layout()
    for leader in leader_layer(layout):
        if leader['url'].rstrip('/') == a.url.rstrip('/'):
            continue
        code, r = call('POST', leader['url'] + '/node/admin/members', {'url': a.url}, layout['secret'], timeout=100)
        if code == 200 and r:
            print(('가입: ' if r['accepted'] else '거부: ') + r['message'])
            return 0 if r['accepted'] else 1
    print('가입 요청을 받아 준 노드가 없다')
    return 1


def cmd_join(a):
    layout = load_layout()
    if any(n['id'] == a.id for n in layout['nodes']):
        n = node_of(layout, a.id)
    else:
        host = a.host or layout_host(layout)
        n = {'id': a.id, 'port': a.port, 'role': 'leader-layer', 'url': f'http://{host}:{a.port}', 'team': a.team}
        layout['nodes'].append(n)
        save_layout(layout)
    if not is_up(n, layout):
        launch(n, layout)
        wait_up([n], layout)
    print(f"{a.id} 켜짐 (learner로 기다림) — 가입 요청")
    for leader in leader_layer(layout):
        if leader['id'] == a.id:
            continue
        # 주소만 있으면 되지만, ID · 팀도 같이 보내 그 주소의 노드가 맞는지 리더가 한 번 더 보게 한다
        code, r = call('POST', leader['url'] + '/node/admin/members',
                       {'id': a.id, 'team': a.team, 'url': n['url']}, layout['secret'], timeout=100)
        if code == 200 and r:
            print(('가입: ' if r['accepted'] else '거부: ') + r['message'])
            return
    print('가입 요청을 받아 준 노드가 없다')


def cmd_leave(a):
    layout = load_layout()
    for leader in leader_layer(layout):
        if leader['id'] == a.id:
            continue
        code, r = call('DELETE', leader['url'] + f'/node/admin/members/{a.id}', None, layout['secret'], timeout=30)
        if code == 200 and r:
            print(('해지: ' if r['accepted'] else '거부: ') + r['message'])
            if r['accepted'] and not a.keep_running:
                n = next((x for x in layout['nodes'] if x['id'] == a.id), None)
                if n:
                    kill(n)
                    layout['nodes'] = [x for x in layout['nodes'] if x['id'] != a.id]
                    save_layout(layout)
            return
    print('해지 요청을 받아 준 노드가 없다')


def cmd_resume(a):
    layout = load_layout()
    for n in leader_layer(layout):
        code, r = call('POST', n['url'] + '/node/admin/resume', None, layout['secret'], timeout=10)
        if code == 200 and r:
            print(r['message'])
            return


def await_cond(what, cond, seconds=40):
    until = time.time() + seconds
    while time.time() < until:
        try:
            if cond():
                print(f"  ✔ {what}")
                return True
        except Exception:
            pass
        time.sleep(0.5)
    print(f"  ✘ {what} — {seconds}초 안에 안 됨")
    return False


def actions_about(layout, suspect):
    for n in leader_layer(layout):
        code, st = call('GET', n['url'] + '/node/admin/status', secret=layout['secret'], timeout=3)
        if code == 200 and st and not st.get('outReason'):
            return [v['action'] for v in st.get('verdicts') or [] if v.get('suspectId') == suspect]
    return []


def same_heads(layout, ids=None):
    heads = set()
    for n in layout['nodes']:
        if ids and n['id'] not in ids:
            continue
        code, s = call('GET', n['url'] + '/node/ledger/status', secret=layout['secret'], timeout=3)
        if code != 200 or not s['chainValid']:
            return False
        heads.add((s['height'], s['headHash']))
    return len(heads) == 1


def cmd_scenario(a):
    """시연 순서 (계획 8단계 체크리스트)를 빈 장부에서 자동으로 돌려 본다"""
    a.fresh, a.team = True, None
    cmd_start(a)
    layout = load_layout()
    ok = True
    print('\n① 기록 30건 — 9대 모두 같은 체인')
    write_many(layout, 30)
    ok &= await_cond('9대 마지막 해시 같음', lambda: same_heads(layout))

    print('\n② 리더를 끈다 → 새 리더 · 기록 계속')
    cmd_kill_leader(a)
    old = next(n for n in leader_layer(layout) if not is_up(n, layout))
    launch(old, layout)
    wait_up([old], layout)
    write_many(layout, 5)
    ok &= await_cond(f"{old['id']} 다시 켜서 따라잡음", lambda: same_heads(layout))

    leader = find_leader(layout)
    suspect = next(n for n in leader_layer(layout) if n['id'] != leader['id'])
    print(f"\n③ 팀 리더 {suspect['id']}의 블록 #20을 꼼꼼하게 변조(해시까지 다시 계산) → 신고 → #k → 제외 → 복구")
    a.id, a.block, a.rewrite, a.keep_off = suspect['id'], 20, True, False
    kill(suspect)
    wait_down(suspect, layout)
    time.sleep(1)
    tamper(layout, suspect['id'], 20, True)
    launch(suspect, layout)
    wait_up([suspect], layout)
    write_many(layout, 3)
    ok &= await_cond(f"{suspect['id']} 판정 기록: 제외 → 복귀",
                     lambda: actions_about(layout, suspect['id'])[-2:] == ['EXCLUDED', 'RESTORED'])
    ok &= await_cond('9대 같은 체인', lambda: same_heads(layout))

    member = next(n for n in layout['nodes'] if n['role'] == 'member')
    print(f"\n④ 팀원 {member['id']}의 블록 #10 변조 → 팀 리더가 신고 → 제외 → 복구")
    kill(member)
    wait_down(member, layout)
    time.sleep(1)
    tamper(layout, member['id'], 10, False)
    launch(member, layout)
    wait_up([member], layout)
    write_many(layout, 3)
    ok &= await_cond(f"{member['id']} 판정 기록: 제외 → 복귀",
                     lambda: actions_about(layout, member['id'])[-2:] == ['EXCLUDED', 'RESTORED'])
    ok &= await_cond('9대 같은 체인', lambda: same_heads(layout))

    print('\n⑤ 4번째 팀 D 가입 (learner → 승격) → 해지')
    a.id, a.team, a.port, a.host, a.keep_running = 'D1', 'D', 9004, None, False
    cmd_join(a)
    write_many(layout, 3)
    layout = load_layout()
    ok &= await_cond('D1도 같은 체인', lambda: same_heads(layout))
    cmd_leave(a)
    layout = load_layout()
    write_many(layout, 2)
    print('\n최종 상태')
    cmd_status(a)
    print('\n시연 순서 전체: ' + ('통과' if ok else '실패한 단계가 있다'))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('start')
    p.add_argument('--fresh', action='store_true', help='장부 · Raft 상태 · 키를 지우고 새로 시작')
    p.add_argument('--patrol', action='store_true', help='순회 검사 켜기 (시연 설정)')
    p.add_argument('--team', help='PC 1대 = 팀 1개 모드: 이 PC의 팀 ID (A · B · C)')
    p.add_argument('--host', default='localhost',
                   help='이 PC의 IP — 노드가 서로를 부르는 주소. 다른 PC의 노드를 붙일 거면 PC 1대 모드에서도 준다')
    p.add_argument('--leaders', help='리더 층 처음 멤버: A1=http://ip:9001,B1=...,C1=... (팀 모드)')
    p.add_argument('--members', type=int, default=2, help='팀마다 팀원 수')
    p.add_argument('--secret', default=SECRET_DEFAULT)
    p.add_argument('--java')
    p.add_argument('--heartbeat-timeout', type=int, help='리더 소식이 몇 초 없으면 선거 (기본 4, 미정사항 7번)')
    for name in ('stop', 'up', 'report'):
        q = sub.add_parser(name)
        q.add_argument('id', nargs='?' if name == 'stop' else None)
    sub.add_parser('status')
    q = sub.add_parser('write')
    q.add_argument('count', nargs='?', type=int, default=10)
    sub.add_parser('kill-leader')
    q = sub.add_parser('tamper')
    q.add_argument('id')
    q.add_argument('--block', type=int, default=1)
    q.add_argument('--rewrite', action='store_true')
    q.add_argument('--keep-off', action='store_true', help='고친 뒤 다시 켜지 않음')
    q = sub.add_parser('join')
    q.add_argument('id')
    q.add_argument('--team', required=True)
    q.add_argument('--port', type=int, default=9004)
    q.add_argument('--host')
    q = sub.add_parser('new-node', help='새 팀의 노드 프로세스만 켠다 (다른 PC에서도) — 가입은 FairChain 점검 화면이나 add')
    q.add_argument('id')
    q.add_argument('--team', required=True)
    q.add_argument('--port', type=int, default=9001)
    q.add_argument('--host', help='다른 노드가 이 노드에 접속할 이 PC의 IP')
    q.add_argument('--leaders', help='붙을 그룹의 처음 멤버: A1=http://ip:9001,B1=...,C1=... (이 PC에 구성이 없을 때)')
    q.add_argument('--members', type=int, default=0, help='같이 켤 팀원 수')
    q.add_argument('--secret', default=SECRET_DEFAULT, help='그룹의 노드 공유 비밀값 (이 PC에 구성이 없을 때)')
    q.add_argument('--java')
    q = sub.add_parser('add', help='주소만으로 가입 요청 (FairChain 점검 화면의 "노드 추가"와 같다)')
    q.add_argument('url')
    q = sub.add_parser('leave')
    q.add_argument('id')
    q.add_argument('--keep-running', action='store_true')
    sub.add_parser('resume')
    q = sub.add_parser('scenario')
    q.add_argument('--patrol', action='store_true')
    q.add_argument('--members', type=int, default=2)
    q.add_argument('--secret', default=SECRET_DEFAULT)
    q.add_argument('--java')
    q.add_argument('--host', default='localhost')
    q.add_argument('--leaders')
    q.add_argument('--heartbeat-timeout', type=int)
    q = sub.add_parser('failover', help='리더 끄기를 N번 되풀이해 새 리더까지 걸린 시간을 잰다')
    q.add_argument('--times', type=int, default=5)
    sub.add_parser('watch-failover', help='PC 3대: 다른 PC에서 리더를 끄면 새 리더까지 시간을 잰다')
    a = ap.parse_args()
    handler = {'start': cmd_start, 'stop': cmd_stop, 'up': cmd_up, 'status': cmd_status, 'write': cmd_write,
               'kill-leader': cmd_kill_leader, 'tamper': cmd_tamper, 'report': cmd_report, 'join': cmd_join,
               'leave': cmd_leave, 'resume': cmd_resume, 'scenario': cmd_scenario, 'failover': cmd_failover,
               'watch-failover': cmd_watch_failover, 'new-node': cmd_new_node, 'add': cmd_add}[a.cmd]
    sys.exit(handler(a) or 0)


if __name__ == '__main__':
    if os.name == 'nt':
        sys.stdout.reconfigure(encoding='utf-8')
    main()
