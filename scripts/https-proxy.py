#!/usr/bin/env python3
"""예제서버(8080) 앞에 https(8443)를 하나 세운다 — 휴대폰에서 카메라(QR 스캔)를 쓰기 위한 것.

브라우저는 localhost가 아니면 https에서만 카메라를 열어 준다. 인증서는 직접 만든 것이라
처음 접속할 때 경고가 뜨는데, "고급 → 계속"을 누르면 된다. 시연용이며 실제 서비스에는 쓰지 않는다.

  python3 scripts/https-proxy.py [https 포트=8443] [대상 포트=8080]
"""
import os, socket, ssl, subprocess, sys, threading

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 8443
TARGET = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'data', 'https')
CERT, KEY = os.path.join(DIR, 'cert.pem'), os.path.join(DIR, 'key.pem')

if not os.path.exists(CERT):
    os.makedirs(DIR, exist_ok=True)
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '30', '-subj', '/CN=fairticket.local',
                    '-keyout', KEY, '-out', CERT], check=True, capture_output=True)

context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(CERT, KEY)


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def serve(raw):
    try:
        client = context.wrap_socket(raw, server_side=True)
        upstream = socket.create_connection(('127.0.0.1', TARGET), 5)
    except (OSError, ssl.SSLError):
        raw.close()
        return
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    pipe(upstream, client)


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('0.0.0.0', LISTEN))
server.listen(64)
print(f'https://<이 컴퓨터 IP>:{LISTEN} → http://localhost:{TARGET}', flush=True)
while True:
    conn, _ = server.accept()
    threading.Thread(target=serve, args=(conn,), daemon=True).start()
