#!/bin/zsh
# JMeter를 여러 개 띄워 같은 순간에 대기열에 들어가게 한다 (시나리오 A만).
# macOS는 프로세스 하나가 스레드를 약 6천 개까지만 만들 수 있어서, 6천 명을 넘기려면 나눠 띄워야 한다.
#
#   ./loadtest/run-multi.sh 이름 프로세스수 프로세스당인원 [진입까지 초=70]
#   예) 1만 명:  ./loadtest/run-multi.sh max10000 2 5000
#
# 먼저 서버를 미리 만든 회원과 함께 띄우고 좌석을 넣어 둔다:
#   EXAMPLE_LOAD_MEMBERS=15000 EXAMPLE_OPTS="--server.tomcat.max-connections=40000 --server.tomcat.accept-count=10000 \
#     --server.tomcat.keep-alive-timeout=900000 --server.tomcat.threads.max=400 --server.tomcat.max-keep-alive-requests=-1" \
#     ./scripts/run-local.sh --mem && python3 scripts/seed-seats.py 2 20 30
NAME=$1; PROCS=$2; PER=$3; DELAY=${4:-70}
cd "${0:a:h}/.."
OUT=loadtest/results/$NAME
rm -rf $OUT; mkdir -p $OUT
AT=$(( ($(date +%s) + DELAY) * 1000 ))
echo "대기열 진입 예정: $(date -r $((AT / 1000)) +%H:%M:%S)"
for i in $(seq 1 $PROCS); do
  JVM_ARGS="-Xms1g -Xmx3g -Xss256k" jmeter -n -t loadtest/fairticket.jmx -Jusers=$PER -Jseats=600 -JrampUp=20 \
    -Jpremade=true -JuserOffset=$(((i-1)*PER)) -JraceUsers=0 -JmacroUsers=0 -Jtag=p$i -JenterAt=$AT \
    -j $OUT/jmeter$i.log -l $OUT/p$i.jtl > $OUT/out$i.txt 2>&1 &
done
wait
head -1 $OUT/p1.jtl > $OUT/all.jtl
for i in $(seq 1 $PROCS); do tail -n +2 $OUT/p$i.jtl >> $OUT/all.jtl; done
python3 loadtest/summarize.py $OUT/all.jtl
python3 scripts/raft_nodes.py status | tail -4
