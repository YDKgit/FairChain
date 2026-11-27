#!/bin/zsh
# 로컬 전체 실행 (macOS · Linux): Redis · 노드 · FairChain · 예제서버를 창 없이 띄운다.
# Redis는 진짜 redis-server를 쓴다 (없으면: brew install redis). 끄면 대기열 데이터는 사라진다.
# Windows는 scripts\run-local.ps1.
#
#   ./scripts/run-local.sh                 빌드 후 실행 (DB는 data/ 아래 H2 파일, 다시 켜도 남음)
#   ./scripts/run-local.sh --no-build      빌드 없이 실행
#   ./scripts/run-local.sh --mem           FairChain · 예제서버 DB를 메모리에, 노드 장부도 새로 (끄면 사라짐 — 시연 · 부하 테스트용)
#   ./scripts/run-local.sh --members 2     팀마다 팀원 노드 2대를 더 띄움 (기본 0: 리더 층 A1 · B1 · C1만)
#   ./scripts/run-local.sh --no-queue      예제서버가 대기열을 거치지 않음
#   ./scripts/run-local.sh --mem --fairchain-only --node-host 192.168.0.12
#                                          PC를 나눠 띄울 때: 이 PC에는 Redis · FairChain만. 노드는 그 IP의 9001~9003, 예제서버는 다른 PC가 띄운다
#   ./scripts/stop-local.sh                전부 종료
#
# 로그는 data/logs/. 계정 · 키는 로컬 시연용 고정값이다 (아래 출력). 밖에서 접속되는 곳에 띄울 때는 환경변수로 바꿀 것.
set -e
cd "${0:a:h}/.."

BUILD=1; MEM=0; MEMBERS=0; QUEUE=true; ONLY_FC=0; NODE_HOST=localhost
while [[ $# -gt 0 ]]; do
  case $1 in
    --no-build) BUILD=0 ;;
    --mem) MEM=1 ;;
    --members) MEMBERS=$2; shift ;;
    --no-queue) QUEUE=false ;;
    --fairchain-only) ONLY_FC=1 ;;
    --node-host) NODE_HOST=$2; shift ;;
    *) echo "모르는 옵션: $1"; exit 1 ;;
  esac
  shift
done

export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null)}
NODE_SECRET=${NODE_SECRET:-local-demo-change-me}
API_KEY=${FAIRCHAIN_API_KEY:-key-localdemo1234}
ADMIN_PW=${FAIRCHAIN_ADMIN_PASSWORD:-admin1234}
OPERATOR_PW=${EXAMPLE_OPERATOR_PASSWORD:-operator1234}
STAFF_PW=${EXAMPLE_STAFF_PASSWORD:-staff1234}
LOG=data/logs; mkdir -p $LOG

up() { nc -z localhost $1 2>/dev/null; }
wait_port() {   # 포트, 이름
  for k in $(seq 1 240); do up $1 && { echo "  $2 준비됨 (:$1)"; return; }; sleep 0.5; done
  echo "$2 이(가) 시작되지 않았습니다. $LOG 의 로그를 확인하세요"; exit 1
}

PORTS=(9001 9002 9003 8081 8080); [[ $ONLY_FC == 1 ]] && PORTS=(8081)
for p in $PORTS; do
  up $p && { echo "포트 $p 이(가) 이미 사용 중입니다. ./scripts/stop-local.sh 로 이전 실행을 종료하세요"; exit 1; }
done

if [[ $BUILD == 1 ]]; then
  if [[ $ONLY_FC == 1 ]]; then ./gradlew :fairchain:bootJar -q; else ./gradlew :node:bootJar :fairchain:bootJar :example-server:bootJar -q; fi
fi

echo '[1/4] Redis'
if up 6379; then
  echo '  이미 실행 중인 Redis를 사용합니다 (:6379)'
elif command -v redis-server > /dev/null; then
  # 디스크에 저장하지 않는다 — 대기열 · 입장권은 서버를 다시 켜면 비우는 데이터다
  nohup redis-server --port 6379 --bind 127.0.0.1 --save '' --appendonly no > $LOG/redis.log 2>&1 &
  echo $! > $LOG/redis.pid
  wait_port 6379 Redis
else
  echo '  redis-server가 없습니다. 설치한 뒤 다시 실행하세요:  brew install redis'
  exit 1
fi

if [[ $ONLY_FC == 1 ]]; then
  echo "[2/4] 노드는 $NODE_HOST 의 9001~9003을 씁니다"
  for p in 9001 9002 9003; do nc -z -G 2 $NODE_HOST $p 2>/dev/null || echo "  경고: $NODE_HOST:$p 에 닿지 않습니다 (노드가 꺼져 있거나 방화벽 · 공유기가 막음)"; done
else
echo "[2/4] 노드 (리더 층 3대 + 팀마다 팀원 ${MEMBERS}대)"
FRESH=(); [[ $MEM == 1 ]] && FRESH=(--fresh)
python3 scripts/raft_nodes.py start $FRESH --members $MEMBERS --secret $NODE_SECRET > $LOG/nodes.out 2>&1 || { cat $LOG/nodes.out; exit 1; }
for p in 9001 9002 9003; do wait_port $p "노드 :$p"; done
fi

echo '[3/4] FairChain'
if [[ $MEM == 1 ]]; then
  FC_DB='jdbc:h2:mem:fairchain;MODE=MySQL;DB_CLOSE_DELAY=-1'; EX_DB='jdbc:h2:mem:example;MODE=MySQL;DB_CLOSE_DELAY=-1'
else
  FC_DB="jdbc:h2:file:$PWD/data/fairchain;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"; EX_DB="jdbc:h2:file:$PWD/data/example;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"
fi
DB_URL=$FC_DB DB_USERNAME=sa DB_PASSWORD= nohup java -jar fairchain/build/libs/fairchain-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --fairchain.nodes.api-key=$NODE_SECRET \
  --fairchain.nodes.urls=http://$NODE_HOST:9001,http://$NODE_HOST:9002,http://$NODE_HOST:9003 \
  --fairchain.seed.client-id=example-server --fairchain.seed.api-key=$API_KEY \
  --fairchain.seed.operator-login-id=admin --fairchain.seed.operator-password=$ADMIN_PW \
  ${=FAIRCHAIN_OPTS} > $LOG/fairchain.log 2>&1 &
echo $! > $LOG/fairchain.pid
wait_port 8081 FairChain

if [[ $ONLY_FC == 1 ]]; then
  ME=$(ipconfig getifaddr en0 2>/dev/null || echo 내IP)
  cat <<EOF

FairChain만 실행 완료 (예제서버 · 노드는 다른 PC)
  점검 화면   http://localhost:8081/inspector.html   admin / $ADMIN_PW
  예제서버 PC에 줄 값   FAIRCHAIN_URL=http://$ME:8081   FAIRCHAIN_API_KEY=$API_KEY
  노드 PC에 줄 값       --secret $NODE_SECRET
  끄기        ./scripts/stop-local.sh
EOF
  exit 0
fi

echo '[4/4] 예제서버'
# 부하 테스트(1만 명 동시 접속)를 서버를 다시 켜지 않고 돌릴 수 있도록 연결 한도를 처음부터 넉넉히 둔다 (기본값은 8,192개)
EXAMPLE_OPTS=${EXAMPLE_OPTS:---server.tomcat.max-connections=40000 --server.tomcat.accept-count=10000 --server.tomcat.keep-alive-timeout=900000 --server.tomcat.threads.max=400 --server.tomcat.max-keep-alive-requests=-1}
FAIRCHAIN_URL=http://localhost:8081 FAIRCHAIN_API_KEY=$API_KEY EXAMPLE_DB_URL=$EX_DB \
EXAMPLE_OPERATOR_PASSWORD=$OPERATOR_PW EXAMPLE_STAFF_PASSWORD=$STAFF_PW EXAMPLE_QUEUE_ENABLED=$QUEUE \
  nohup java -jar example-server/build/libs/example-server-0.0.1-SNAPSHOT.jar ${=EXAMPLE_OPTS} > $LOG/example.log 2>&1 &
echo $! > $LOG/example.pid
wait_port 8080 예제서버

# 휴대폰에서 카메라(QR 스캔)를 쓰려면 https가 필요하다 — 8443에서 예제서버로 넘겨 준다
nohup python3 scripts/https-proxy.py 8443 8080 > $LOG/https.log 2>&1 &
echo $! > $LOG/https.pid

cat <<EOF

실행 완료
  예제 예매 사이트     http://localhost:8080          회원: 로그인 화면의 시연 계정 (비밀번호 demo1234)
    운영자            http://localhost:8080/admin    operator / $OPERATOR_PW
    게이트 스태프      http://localhost:8080/gate     staff / $STAFF_PW
    휴대폰에서        https://$(ipconfig getifaddr en0 2>/dev/null || echo 내IP):8443   (인증서 경고는 '계속' — 카메라 스캔 가능)
  FairChain 점검 화면  http://localhost:8081/inspector.html   admin / $ADMIN_PW
  FairChain API Key    $API_KEY
  노드 상태            python3 scripts/raft_nodes.py status
  끄기                 ./scripts/stop-local.sh
EOF
