#!/bin/zsh
# scripts/run-local.sh 로 띄운 것을 모두 끈다 (DB 파일 · 노드 장부는 남김)
cd "${0:a:h}/.."
LOG=data/logs
for f in example fairchain redis; do
  [[ -f $LOG/$f.pid ]] && kill $(cat $LOG/$f.pid) 2>/dev/null
  rm -f $LOG/$f.pid
done
python3 scripts/raft_nodes.py stop > /dev/null 2>&1 || true
sleep 1
lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep -E ':(8080|8081|900[1-3]|90[1-3][0-9]) ' || echo '모두 종료했습니다'
