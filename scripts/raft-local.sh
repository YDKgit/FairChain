#!/bin/sh
# 리더 층 Raft 노드 실행 · 시연 도구 (scripts/raft_nodes.py)를 부르는 짧은 이름 (macOS · Linux)
#   ./scripts/raft-local.sh start --fresh          PC 1대: A1 · B1 · C1 + 팀원 6대
#   ./scripts/raft-local.sh status | kill-leader | scenario | stop
#   ./scripts/raft-local.sh start --team B --host 192.168.0.12 \
#       --leaders A1=http://192.168.0.11:9001,B1=http://192.168.0.12:9001,C1=http://192.168.0.13:9001
# 모든 명령: python3 scripts/raft_nodes.py --help
cd "$(dirname "$0")/.." || exit 1
exec python3 scripts/raft_nodes.py "$@"
