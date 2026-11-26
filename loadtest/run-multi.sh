#!/bin/zsh
# JMeter를 여러 개 띄워 같은 순간에 대기열에 들어가게 한다 (시나리오 A만).
# macOS는 프로세스 하나가 스레드를 약 6천 개까지만 만들 수 있어서, 6천 명을 넘기려면 나눠 띄워야 한다.
#
#   ./loadtest/run-multi.sh 이름 프로세스수 프로세스당인원 [진입까지 초=70]
#   예) 1만 명:  ./loadtest/run-multi.sh max10000 2 5000
#
# 서버는 미리 만든 회원과 함께 한 번만 띄워 두면 된다:  EXAMPLE_LOAD_MEMBERS=15000 ./scripts/run-local.sh --mem
# 돌릴 때마다 600석짜리 공연을 새로 만들어 거기서 돌리므로, 서버를 다시 켜지 않고 몇 번이든 돌릴 수 있다.
# 이미 있는 공연에서 돌리려면 CONCERT=2 ./loadtest/run-multi.sh … (그 공연에 빈 좌석이 있어야 한다)
NAME=$1; PROCS=$2; PER=$3; DELAY=${4:-70}
cd "${0:a:h}/.."
OUT=loadtest/results/$NAME-$(date +%m%d-%H%M%S)   # 실행마다 새 폴더 — 앞 결과를 지우지 않는다
rm -rf $OUT; mkdir -p $OUT
CONCERT=${CONCERT:-$(python3 loadtest/new-concert.py)} || exit 1
echo "공연 #$CONCERT 에서 돌립니다"
# AT=19:42 처럼 시각을 주면 그 시각에 진입한다 (없으면 지금부터 DELAY초 뒤). TITLE=test1 은 새 공연의 이름
if [[ -n $AT ]]; then AT=$(( $(date -j -f "%Y-%m-%d %H:%M:%S" "$(date +%Y-%m-%d) ${AT}:00" +%s) * 1000 )); else AT=$(( ($(date +%s) + DELAY) * 1000 )); fi
echo "대기열 진입 예정: $(date -r $((AT / 1000)) +%H:%M:%S)"
python3 loadtest/collect.py begin $OUT "run-multi: JMeter ${PROCS}개 × ${PER}명 · 공연 #$CONCERT · 진입 $(date -r $((AT / 1000)) +%H:%M:%S)"
for i in $(seq 1 $PROCS); do
  JVM_ARGS="-Xms1g -Xmx3g -Xss256k" jmeter -n -t loadtest/fairticket.jmx -Jusers=$PER -JconcertId=$CONCERT -Jseats=600 -JrampUp=20 \
    -Jpremade=true -JuserOffset=$(((i-1)*PER)) -JraceUsers=0 -JmacroUsers=0 -Jtag=p$i -JenterAt=$AT \
    -j $OUT/jmeter$i.log -l $OUT/p$i.jtl > $OUT/out$i.txt 2>&1 &
done
wait
head -1 $OUT/p1.jtl > $OUT/all.jtl
for i in $(seq 1 $PROCS); do tail -n +2 $OUT/p$i.jtl >> $OUT/all.jtl; done
python3 loadtest/summarize.py $OUT/all.jtl
python3 scripts/raft_nodes.py status | tail -4
python3 loadtest/collect.py end $OUT      # summary.md + 서버 로그 → $OUT.zip (Claude에게 보낼 묶음)
