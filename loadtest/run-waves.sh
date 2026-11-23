#!/bin/zsh
# 묶음으로 나눠 들어오는 예매: 묶음 인원이 모일 때마다 한꺼번에 대기열에 들어간다.
#
#   [TITLE=공연이름] ./loadtest/run-waves.sh [총 인원=2000] [묶음 인원=200] [묶음 간격 초=5]
#   예) 200명씩 5초 간격으로 2,000명:  TITLE=test2 ./loadtest/run-waves.sh 2000 200 5
#
# 돌릴 때마다 600석짜리 공연을 새로 만들어 거기서 돌린다 (서버를 다시 켤 필요 없음). CONCERT=2 로 기존 공연을 고를 수도 있다.
# JMeter 한 개로 돌리므로 총 인원은 6천 명 아래로.
TOTAL=${1:-2000}; WAVE=${2:-200}; GAP=${3:-5}
cd "${0:a:h}/.."
OUT=loadtest/results/waves
rm -rf $OUT; mkdir -p $OUT
CONCERT=${CONCERT:-$(python3 loadtest/new-concert.py)} || exit 1
RAMP=$(printf "%.0f" $(( TOTAL * 1.0 / WAVE * GAP )))
echo "공연 #$CONCERT: ${WAVE}명씩 ${GAP}초 간격으로 ${TOTAL}명 (약 ${RAMP}초 동안 진입)"
JVM_ARGS="-Xms1g -Xmx3g -Xss256k" jmeter -n -t loadtest/fairticket.jmx -Jusers=$TOTAL -JconcertId=$CONCERT -Jseats=600 -JrampUp=$RAMP \
  -Jpremade=true -JraceUsers=0 -JmacroUsers=0 -JsyncGroup=$WAVE -Jtag=wave \
  -j $OUT/jmeter.log -l $OUT/all.jtl > $OUT/out.txt 2>&1
python3 loadtest/summarize.py $OUT/all.jtl
python3 - $OUT/all.jtl <<'PY'
import csv, sys, collections
c = collections.Counter(int(r['timeStamp']) // 1000 for r in csv.DictReader(open(sys.argv[1], encoding='utf-8')) if r['label'] == 'A-1 대기열 진입')
t0 = min(c)
print('초별 대기열 진입:', ' '.join(f'+{t - t0}s:{n}' for t, n in sorted(c.items())))
PY
python3 scripts/raft_nodes.py status | tail -4
