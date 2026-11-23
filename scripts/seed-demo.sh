#!/bin/bash
# 서버를 켠 뒤 한 번 실행: 시연용 공연과 JMeter용 공연을 한꺼번에 채운다.
#   #2  JMeter용 600석(20줄 x 30석)      #4 #6 #7 #8  일반 예매용 32석
#   #5  취소표 재배정용(32석을 매진시킨 뒤 3장 취소 → 보류)   #1  오늘 공연, 입장 가능한 티켓 3장
set -e
cd "$(dirname "$0")"
python3 seed-seats.py 2 20 30
for c in 4 5 6 7 8; do python3 seed-seats.py "$c" 4 8; done
python3 seed-realloc.py 5 3
python3 seed-entry.py
echo "준비 끝: #2 JMeter용 / #4 #6 #7 #8 예매용 / #5 취소표용 / #1 입장용"
