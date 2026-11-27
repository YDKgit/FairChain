#!/bin/bash
# 다른 PC(macOS · Linux)에서 JMeter만 돌린다. 이 폴더만 있으면 된다 (저장소 필요 없음).
#
#   ./run.sh 예제서버IP 공연번호 진입시각(HH:MM) [인원=5000] [회원 번호 시작=5000]
#   예) ./run.sh 172.30.71.30 12 19:42
#
# 공연번호 · 진입시각은 FairChain PC와 똑같이 준다. 회원 번호 시작은 FairChain PC가 0~4999를 쓰므로 5000.
# 바꿀 수 있는 값: VERIFY(티켓 조회 횟수, 기본 0=안 함) WAIT_MS(응답 대기, 기본 120000) CONNECT_MS(연결 대기, 기본 20000) STAY(예매 뒤 머무르기 ms)
HOST=$1; CONCERT=$2; AT=$3; USERS=${4:-5000}; BASE=${5:-5000}
if [ -z "$AT" ]; then echo "사용법: ./run.sh 예제서버IP 공연번호 진입시각(HH:MM) [인원=5000] [회원 번호 시작=5000]"; exit 1; fi
cd "$(dirname "$0")"
command -v jmeter >/dev/null || { echo "jmeter 명령이 없습니다. JMeter 5.6을 설치하세요 (mac: brew install jmeter)"; exit 1; }
DAY=$(date +%Y-%m-%d)
SEC=$(date -j -f "%Y-%m-%d %H:%M:%S" "$DAY $AT:00" +%s 2>/dev/null || date -d "$DAY $AT:00" +%s) || { echo "시각을 읽지 못했습니다: $AT"; exit 1; }
LEFT=$(( SEC - $(date +%s) ))
if [ $LEFT -lt 40 ]; then echo "진입 시각까지 ${LEFT}초 남았습니다. 로그인에 시간이 걸리니 1분 이상 뒤의 시각을 주세요"; exit 1; fi
ulimit -n 65536 2>/dev/null
OUT=results/$(date +%H%M%S); mkdir -p $OUT
echo "예제서버 $HOST · 공연 #$CONCERT · ${USERS}명 (회원 load$((BASE+1)) ~ load$((BASE+USERS))) · 진입 $AT (${LEFT}초 뒤)"
JVM_ARGS="-Xms1g -Xmx3g -Xss256k" jmeter -n -t fairticket.jmx -Jhttpclient4.time_to_live=3600000 -Jhost=$HOST -JstayMs=${STAY:-0} -JresponseTimeout=${WAIT_MS:-120000} -JconnectTimeout=${CONNECT_MS:-20000} -JverifyMax=${VERIFY:-0} \
  -Jusers=$USERS -JconcertId=$CONCERT -Jseats=0 -JrampUp=20 -Jpremade=true -JuserOffset=$BASE -JraceUsers=0 -JmacroUsers=0 -Jtag=r1 -JenterAt=$(( SEC * 1000 )) \
  -j $OUT/jmeter.log -l $OUT/remote.jtl
command -v python3 >/dev/null && python3 summarize.py $OUT/remote.jtl
echo "결과 파일: $(pwd)/$OUT/remote.jtl  (FairChain PC로 보내면 합쳐서 볼 수 있습니다)"
