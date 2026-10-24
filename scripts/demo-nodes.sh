#!/bin/zsh
# 시연용 노드 N대를 터미널 창 없이 한 번에 켜고 끈다. 장부는 data/node1~N, 로그는 data/nodeN.log
#   ./scripts/demo-nodes.sh start 10     노드 10대 켜기 (9001~9010). 이미 장부가 있는 노드는 이어서, 새 노드는 다른 노드에서 따라잡음
#   ./scripts/demo-nodes.sh stop 3       노드3만 끄기          (시연 ⑦ ⑧)
#   ./scripts/demo-nodes.sh up 3         노드3만 다시 켜기
#   ./scripts/demo-nodes.sh status       노드별 블록 수 · 체인 상태
#   ./scripts/demo-nodes.sh urls         FairChain에 넘길 노드 주소 목록
#   ./scripts/demo-nodes.sh stop         전부 끄기 (장부는 남김)
set -e
cd "${0:a:h}/.."
DIR=${DEMO_NODES_DIR:-./data}
BASE=${DEMO_BASE_PORT:-9000}
mkdir -p $DIR

port() { echo $((BASE + $1)); }
count() { cat $DIR/nodes.count 2>/dev/null || echo 0; }

launch() {   # $1 = 번호, $2 = 전체 대수
  local i=$1 n=$2 peers=()
  for j in $(seq 1 $n); do [[ $j != $i ]] && peers+=("http://localhost:$(port $j)"); done
  if nc -z localhost $(port $i) 2>/dev/null; then echo "노드${i}(:$(port $i))는 이미 켜져 있어요"; return; fi
  NODE_PORT=$(port $i) NODE_DB=$DIR/node$i NODE_PEERS=${(j:,:)peers} \
    nohup java -Xmx256m -jar node/build/libs/node-0.0.1-SNAPSHOT.jar >> $DIR/node$i.log 2>&1 &
  echo $! > $DIR/node$i.pid
}

wait_up() {
  for k in $(seq 1 120); do nc -z localhost $(port $1) 2>/dev/null && return; sleep 0.5; done
  echo "노드${1}이 안 켜져요. $DIR/node$1.log 를 확인하세요"
}

case $1 in
start)
  n=${2:-3}
  [[ $n -ge 3 ]] || { echo "노드는 3대 이상"; exit 1; }
  echo $n > $DIR/nodes.count
  for i in $(seq 1 $n); do launch $i $n; done
  echo "노드 ${n}대 켜는 중..."
  for i in $(seq 1 $n); do wait_up $i; done
  echo "켜짐: $(port 1) ~ $(port $n) (과반 $(( n / 2 + 1 ))표)"
  echo "FairChain은 이렇게 켜세요:"
  echo "  java -jar fairchain/build/libs/fairchain-0.0.1-SNAPSHOT.jar --fairchain.nodes.urls=$($0 urls)"
  ;;
up)
  n=$(count); [[ $n -ge 3 ]] || { echo "먼저 '$0 start N'"; exit 1; }
  launch $2 $n; wait_up $2; echo "노드${2}(:$(port $2)) 켜짐"
  ;;
stop)
  if [[ -n $2 ]]; then
    kill $(cat $DIR/node$2.pid) 2>/dev/null && echo "노드${2}(:$(port $2)) 끔" || echo "노드${2}는 이미 꺼져 있어요"
  else
    for i in $(seq 1 $(count)); do kill $(cat $DIR/node$i.pid 2>/dev/null) 2>/dev/null || true; done
    echo "노드를 모두 껐어요 (장부는 그대로)"
  fi
  ;;
status)
  for i in $(seq 1 $(count)); do
    s=$(curl -s -m 2 -H "X-Node-Secret: ${NODE_SECRET:-local-demo-change-me}" localhost:$(port $i)/node/ledger/status || true)
    if [[ -z $s ]]; then echo "노드$i  꺼짐"; else
      echo "노드$i  블록 $(echo $s | grep -o '"height":[0-9]*' | cut -d: -f2)개 · $(echo $s | grep -o '"chainValid":[a-z]*' | cut -d: -f2) · 마지막 해시 $(echo $s | grep -o '"headHash":"[0-9a-f]\{12\}' | cut -d'"' -f4)…"
    fi
  done
  ;;
urls)
  n=$(count); list=(); for i in $(seq 1 $n); do list+=("http://localhost:$(port $i)"); done; echo ${(j:,:)list}
  ;;
*)
  sed -n '2,9p' $0
  ;;
esac
