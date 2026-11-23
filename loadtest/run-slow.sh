#!/bin/zsh
# 순차 진입 예매: 한 명씩 일정한 간격으로 대기열에 들어가 예매한다 (한꺼번에 몰아넣지 않는다 — 시연 · 사람이 같이 들어가 볼 때).
#
#   [TITLE=공연이름] [SEATS=1000] ./loadtest/run-slow.sh [인원=1500] [간격 초=0.1]
#   예) 시연: 1,000석 공연에 1,500명이 0.1초 간격으로   TITLE=시연 ./loadtest/run-slow.sh
#       100명이 1초 간격, 600석                          SEATS=600 ./loadtest/run-slow.sh 100 1
#
# 돌릴 때마다 공연을 새로 만들어 거기서 돌린다 (서버를 다시 켤 필요 없음). CONCERT=2 로 기존 공연을 고를 수도 있다.
# JMeter 한 개로 돌리므로 인원은 6천 명 아래로. 좌석은 한 줄 50석씩 만든다.
USERS=${1:-1500}; GAP=${2:-0.1}; SEATS=${SEATS:-1000}
cd "${0:a:h}/.."
OUT=loadtest/results/slow
rm -rf $OUT; mkdir -p $OUT
CONCERT=${CONCERT:-$(python3 loadtest/new-concert.py $(( (SEATS + 49) / 50 )) 50)} || exit 1
# 회원 한 명은 한 공연에 한 장만 살 수 있다. 기존 공연에서 다시 돌릴 때를 위해 실행마다 다른 회원 묶음을 쓴다 (미리 만든 15,000명 안에서)
OFFSET=${OFFSET:-$(( ($(date +%s) / 10 % 80) * 100 ))}
RAMP=$(printf "%.0f" $(( USERS * GAP )))
echo "공연 #$CONCERT (${SEATS}석): ${USERS}명이 ${GAP}초 간격으로 들어갑니다 (약 ${RAMP}초 동안)"
JVM_ARGS="-Xms1g -Xmx3g -Xss256k" jmeter -n -t loadtest/fairticket.jmx -Jusers=$USERS -JconcertId=$CONCERT -Jseats=$SEATS -JrampUp=$RAMP \
  -Jpremade=true -JraceUsers=0 -JmacroUsers=0 -JsyncGroup=1 -JuserOffset=$OFFSET -Jtag=slow \
  -j $OUT/jmeter.log -l $OUT/all.jtl > $OUT/out.txt 2>&1
python3 loadtest/summarize.py $OUT/all.jtl
python3 scripts/raft_nodes.py status | tail -4
