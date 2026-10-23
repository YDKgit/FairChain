#!/bin/zsh
# 시연용: 꺼져 있는 노드의 장부(H2 파일)를 직접 고치거나 지운다.
#   ./scripts/demo-tamper-node.sh 3          노드3의 1번 블록 해시값을 ffff…로 바꿈 → 켜면 "체인 깨짐"
#   ./scripts/demo-tamper-node.sh 3 --wipe   노드3 장부를 지움 → 켜면 다른 노드에서 처음부터 다시 받음
# 노드가 켜져 있으면 H2 파일이 잠겨 있으므로, 그 노드 터미널에서 Ctrl+C로 먼저 끈다.
set -e
cd "${0:a:h}/.."
i=$1
[[ $i == [123] ]] || { echo "사용법: $0 <1|2|3> [--wipe]"; exit 1; }
db=${NODE_DB:-./data/node$i}
if nc -z localhost 900$i 2>/dev/null; then
  echo "노드${i}(:900$i)가 아직 켜져 있어요. 그 터미널에서 Ctrl+C로 끈 뒤 다시 실행하세요."; exit 1
fi
if [[ $2 == --wipe ]]; then
  rm -f "$db.mv.db" "$db.trace.db"
  echo "노드$i 장부를 지웠어요. 노드${i}를 다시 켜면 다른 노드에서 블록을 처음부터 받아요."; exit 0
fi
[[ -f $db.mv.db ]] || { echo "$db.mv.db 가 없어요"; exit 1; }
tmp=$(mktemp -d)
unzip -q -j node/build/libs/node-0.0.1-SNAPSHOT.jar 'BOOT-INF/lib/h2-*.jar' -d "$tmp"
java -cp "$tmp"/h2-*.jar org.h2.tools.Shell -url "jdbc:h2:file:$db" -user "" -password "" -sql \
  "UPDATE BLOCK SET RECORD_HASH='$(printf 'f%.0s' {1..64})' WHERE BLOCK_INDEX=1; SELECT BLOCK_INDEX, LEFT(RECORD_HASH, 16) AS RECORD_HASH FROM BLOCK WHERE BLOCK_INDEX <= 3"
rm -rf "$tmp"
echo "노드${i}의 1번 블록을 고쳤어요. 노드${i}를 다시 켜면 \"체인 깨짐\"으로 표시되고 투표에서 빠져요."
