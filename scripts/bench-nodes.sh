#!/bin/zsh
# 측정용: 노드 N대를 띄우고, 기록을 하나씩 보내 확정까지 걸린 시간을 잰다.
#   ./scripts/bench-nodes.sh start 10       노드 10대를 9101~9110에 띄움 (장부는 data/bench, 시연용 노드와 별개)
#   ./scripts/bench-nodes.sh run 50         노드1(9101)에 기록 50건을 차례로 보내 1건당 확정 시간 측정
#   ./scripts/bench-nodes.sh kill 3         노드3만 끔 (장애 상태 측정용)
#   ./scripts/bench-nodes.sh status         노드별 블록 수 · 체인 상태
#   ./scripts/bench-nodes.sh stop           전부 끄고 장부 삭제
set -e
cd "${0:a:h}/.."
DIR=./data/bench
BASE=${BENCH_BASE_PORT:-9100}
SECRET=bench-secret
mkdir -p $DIR

port() { echo $((BASE + $1)); }
count() { cat $DIR/count 2>/dev/null || echo 0; }

case $1 in
start)
  n=${2:-3}
  [[ $n -ge 3 ]] || { echo "노드는 3대 이상"; exit 1; }
  [[ -f $DIR/count ]] && { echo "이미 켜져 있어요. 먼저 '$0 stop'"; exit 1; }
  cp node/build/libs/node-0.0.1-SNAPSHOT.jar $DIR/node.jar     # 빌드 중에 jar가 바뀌어도 측정 노드는 그대로
  echo $n > $DIR/count
  for i in $(seq 1 $n); do
    peers=(); for j in $(seq 1 $n); do [[ $j != $i ]] && peers+=("http://localhost:$(port $j)"); done
    NODE_PORT=$(port $i) NODE_DB=$DIR/node$i NODE_SECRET=$SECRET NODE_PEERS=${(j:,:)peers} \
      nohup java -Xmx256m -jar $DIR/node.jar > $DIR/node$i.log 2>&1 &
    echo $! > $DIR/node$i.pid
  done
  echo "노드 ${n}대 켜는 중..."
  for i in $(seq 1 $n); do
    for k in $(seq 1 120); do nc -z localhost $(port $i) 2>/dev/null && break; sleep 0.5; done
  done
  echo "켜짐: $(port 1) ~ $(port $n)  (과반 $(( n / 2 + 1 ))표)"
  ;;
run)
  total=${2:-50}; target=${3:-1}
  base=$(date +%s)
  : > $DIR/times.txt
  fail=0
  for i in $(seq 1 $total); do
    key=$(printf '%056x%08x' $base $i); hash=$(printf 'b%055x%08x' $base $i)
    out=$(curl -s -m 30 -o $DIR/last.json -w '%{time_total}' -H "X-Node-Secret: $SECRET" -H 'Content-Type: application/json' \
          -d "{\"recordKey\":\"$key\",\"version\":1,\"recordHash\":\"$hash\"}" localhost:$(port $target)/node/records)
    if grep -q '"accepted":true' $DIR/last.json; then echo $out >> $DIR/times.txt; else fail=$((fail + 1)); fi
  done
  n=$(count)
  sort -n $DIR/times.txt | awk -v n=$n -v fail=$fail -v total=$total '
    { t[NR] = $1 * 1000; sum += $1 * 1000 }
    END {
      if (NR == 0) { printf "노드 %d대: 확정 0건 / %d건 (전부 실패)\n", n, total; exit }
      printf "노드 %d대: 확정 %d건 / %d건 (실패 %d)\n", n, NR, total, fail
      printf "  평균 %.1f ms · 중간값 %.1f ms · 95%% %.1f ms · 최대 %.1f ms\n", sum / NR, t[int((NR + 1) / 2)], t[int(NR * 0.95 + 0.5)], t[NR]
    }'
  ;;
kill)
  kill $(cat $DIR/node$2.pid) && echo "노드$2($(port $2)) 끔"
  ;;
status)
  for i in $(seq 1 $(count)); do
    echo "노드$i $(curl -s -m 2 -H "X-Node-Secret: $SECRET" localhost:$(port $i)/node/ledger/status | cut -c1-40)… $(curl -s -m 2 -H "X-Node-Secret: $SECRET" localhost:$(port $i)/node/ledger/status | grep -o '"chainValid":[a-z]*' || echo 꺼짐)"
  done
  ;;
stop)
  for f in $DIR/node*.pid(N); do kill $(cat $f) 2>/dev/null || true; done
  sleep 1
  rm -rf $DIR
  echo "측정용 노드를 모두 끄고 장부를 지웠어요"
  ;;
*)
  sed -n '2,8p' $0
  ;;
esac
