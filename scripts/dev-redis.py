"""로컬 시연용 Redis 대체 서버 — 이 PC에 Redis가 없을 때만 쓴다 (scripts/run-local.ps1이 자동으로 띄움).

FairChain이 StringRedisTemplate으로 쓰는 명령만 메모리에서 흉내 낸다:
문자열(SET/GET/INCR/EXPIRE) · 리스트(RPUSH/LPUSH/LPOP/LLEN) · 정렬 집합(ZADD/ZSCORE/ZRANK/ZCARD/ZPOPMIN/ZREM/ZRANGE)
· 집합(SADD/SMEMBERS) · 키(DEL/EXISTS). 끄면 내용이 사라진다.
부하 테스트(JMeter)나 발표 시연에는 진짜 Redis를 쓸 것 (예: docker run -d -p 6379:6379 redis:7-alpine).

실행: python scripts/dev-redis.py [포트=6379]
"""
import socketserver, sys, threading, time

DATA = {}       # key -> value (bytes | list | dict(member->score) | set)
EXPIRES = {}    # key -> deadline (monotonic seconds)
LOCK = threading.RLock()


def alive(key):
    deadline = EXPIRES.get(key)
    if deadline is not None and time.monotonic() >= deadline:
        DATA.pop(key, None)
        EXPIRES.pop(key, None)
    return key in DATA


def fmt_score(score):
    return format(score, '.17g').encode()


def zsorted(z):
    return sorted(z.items(), key=lambda kv: (kv[1], kv[0]))


class Error(Exception):
    pass


def execute(args):
    cmd = args[0].upper().decode()
    a = args[1:]
    with LOCK:
        if cmd == 'PING':
            return ('simple', b'PONG')
        if cmd in ('HELLO', 'AUTH'):
            raise Error(f"ERR unknown command '{cmd}'")
        if cmd in ('CLIENT', 'SELECT', 'READONLY'):
            return ('simple', b'OK')
        if cmd == 'COMMAND':
            return []
        if cmd == 'INFO':
            return b'# Server\r\nredis_version:7.2.0\r\n'
        if cmd == 'FLUSHALL' or cmd == 'FLUSHDB':
            DATA.clear(); EXPIRES.clear()
            return ('simple', b'OK')
        if cmd in ('SET', 'SETEX', 'PSETEX'):
            if cmd == 'SETEX':
                key, ttl, value = a[0], float(a[1]), a[2]; opts = []
                ttl_s = ttl
            elif cmd == 'PSETEX':
                key, ttl, value = a[0], float(a[1]), a[2]; opts = []
                ttl_s = ttl / 1000
            else:
                key, value, opts = a[0], a[1], [o.upper() for o in a[2:]]
                ttl_s = None
            nx = b'NX' in opts; xx = b'XX' in opts; keep = b'KEEPTTL' in opts
            i = 0
            while i < len(opts):
                if opts[i] == b'EX':
                    ttl_s = float(a[2 + i + 1]); i += 2; continue
                if opts[i] == b'PX':
                    ttl_s = float(a[2 + i + 1]) / 1000; i += 2; continue
                i += 1
            exists = alive(key)
            if (nx and exists) or (xx and not exists):
                return None
            DATA[key] = value
            if ttl_s is not None:
                EXPIRES[key] = time.monotonic() + ttl_s
            elif not keep:
                EXPIRES.pop(key, None)
            return ('simple', b'OK')
        if cmd == 'GET':
            return DATA[a[0]] if alive(a[0]) else None
        if cmd in ('INCR', 'INCRBY', 'DECR'):
            key = a[0]
            step = int(a[1]) if cmd == 'INCRBY' else (-1 if cmd == 'DECR' else 1)
            current = int(DATA[key]) if alive(key) else 0
            current += step
            DATA[key] = str(current).encode()
            return current
        if cmd in ('EXPIRE', 'PEXPIRE'):
            if not alive(a[0]):
                return 0
            seconds = float(a[1]) / (1000 if cmd == 'PEXPIRE' else 1)
            EXPIRES[a[0]] = time.monotonic() + seconds
            return 1
        if cmd in ('TTL', 'PTTL'):
            if not alive(a[0]):
                return -2
            if a[0] not in EXPIRES:
                return -1
            left = EXPIRES[a[0]] - time.monotonic()
            return int(left * 1000) if cmd == 'PTTL' else int(left)
        if cmd in ('DEL', 'UNLINK'):
            n = 0
            for key in a:
                if alive(key):
                    DATA.pop(key); EXPIRES.pop(key, None); n += 1
            return n
        if cmd == 'EXISTS':
            return sum(1 for key in a if alive(key))
        # lists
        if cmd in ('RPUSH', 'LPUSH'):
            lst = DATA[a[0]] if alive(a[0]) else []
            for v in a[1:]:
                if cmd == 'RPUSH':
                    lst.append(v)
                else:
                    lst.insert(0, v)
            DATA[a[0]] = lst
            return len(lst)
        if cmd == 'LPOP':
            if not alive(a[0]):
                return None
            lst = DATA[a[0]]
            if len(a) > 1:
                n = int(a[1]); out = lst[:n]; del lst[:n]
                result = out
            else:
                result = lst.pop(0)
            if not lst:
                DATA.pop(a[0]); EXPIRES.pop(a[0], None)
            return result
        if cmd == 'LLEN':
            return len(DATA[a[0]]) if alive(a[0]) else 0
        if cmd == 'LRANGE':
            if not alive(a[0]):
                return []
            lst = DATA[a[0]]; start, stop = int(a[1]), int(a[2])
            stop = len(lst) - 1 if stop == -1 else stop
            return lst[start:stop + 1]
        # sorted sets
        if cmd == 'ZADD':
            key = a[0]; rest = a[1:]
            while rest and rest[0].upper() in (b'NX', b'XX', b'GT', b'LT', b'CH'):
                rest = rest[1:]
            z = DATA[key] if alive(key) else {}
            added = 0
            for i in range(0, len(rest), 2):
                score, member = float(rest[i]), rest[i + 1]
                if member not in z:
                    added += 1
                z[member] = score
            DATA[key] = z
            return added
        if cmd == 'ZSCORE':
            if not alive(a[0]) or a[1] not in DATA[a[0]]:
                return None
            return fmt_score(DATA[a[0]][a[1]])
        if cmd == 'ZRANK':
            if not alive(a[0]):
                return None
            for i, (member, _) in enumerate(zsorted(DATA[a[0]])):
                if member == a[1]:
                    return i
            return None
        if cmd == 'ZCARD':
            return len(DATA[a[0]]) if alive(a[0]) else 0
        if cmd == 'ZREM':
            if not alive(a[0]):
                return 0
            z = DATA[a[0]]; n = 0
            for member in a[1:]:
                if member in z:
                    del z[member]; n += 1
            if not z:
                DATA.pop(a[0]); EXPIRES.pop(a[0], None)
            return n
        if cmd == 'ZPOPMIN':
            if not alive(a[0]):
                return []
            z = DATA[a[0]]; n = int(a[1]) if len(a) > 1 else 1
            out = []
            for member, score in zsorted(z)[:n]:
                del z[member]
                out += [member, fmt_score(score)]
            if not z:
                DATA.pop(a[0]); EXPIRES.pop(a[0], None)
            return out
        if cmd == 'ZRANGE':
            if not alive(a[0]):
                return []
            items = zsorted(DATA[a[0]]); start, stop = int(a[1]), int(a[2])
            stop = len(items) - 1 if stop == -1 else stop
            chosen = items[start:stop + 1]
            if len(a) > 3 and a[3].upper() == b'WITHSCORES':
                out = []
                for member, score in chosen:
                    out += [member, fmt_score(score)]
                return out
            return [member for member, _ in chosen]
        # sets
        if cmd == 'SADD':
            s = DATA[a[0]] if alive(a[0]) else set()
            before = len(s); s.update(a[1:]); DATA[a[0]] = s
            return len(s) - before
        if cmd == 'SMEMBERS':
            return sorted(DATA[a[0]]) if alive(a[0]) else []
        if cmd == 'KEYS':
            return [k for k in list(DATA) if alive(k)]
        if cmd == 'DBSIZE':
            return sum(1 for k in list(DATA) if alive(k))
        raise Error(f"ERR unknown command '{cmd}'")


def encode(value):
    if value is None:
        return b'$-1\r\n'
    if isinstance(value, tuple) and value[0] == 'simple':
        return b'+' + value[1] + b'\r\n'
    if isinstance(value, bool):
        return b':%d\r\n' % int(value)
    if isinstance(value, int):
        return b':%d\r\n' % value
    if isinstance(value, bytes):
        return b'$%d\r\n%s\r\n' % (len(value), value)
    if isinstance(value, list):
        return b'*%d\r\n' % len(value) + b''.join(encode(v) for v in value)
    raise TypeError(type(value))


class Handler(socketserver.StreamRequestHandler):
    def read_command(self):
        line = self.rfile.readline()
        if not line:
            return None
        if line[:1] != b'*':
            return line.strip().split()
        n = int(line[1:])
        args = []
        for _ in range(n):
            size = int(self.rfile.readline()[1:])
            args.append(self.rfile.read(size + 2)[:-2])
        return args

    def handle(self):
        while True:
            try:
                args = self.read_command()
            except (ConnectionError, ValueError):
                return
            if args is None:
                return
            if not args:
                continue
            try:
                reply = encode(execute(args))
            except Error as e:
                reply = b'-' + str(e).encode() + b'\r\n'
            except Exception as e:  # keep serving; report the problem to the client
                reply = b'-ERR ' + repr(e).encode() + b'\r\n'
            self.wfile.write(reply)
            self.wfile.flush()


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6379
    with Server(('127.0.0.1', port), Handler) as server:
        print(f'mini-redis listening on {port}', flush=True)
        server.serve_forever()
