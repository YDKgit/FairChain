<#
  리더 층 Raft 노드 실행 · 시연 도구 (scripts\raft_nodes.py)를 부르는 짧은 이름.

    .\scripts\raft-local.ps1 start -fresh          PC 1대: A1 · B1 · C1 + 팀원 6대 (빈 장부로)
    .\scripts\raft-local.ps1 status
    .\scripts\raft-local.ps1 kill-leader
    .\scripts\raft-local.ps1 tamper B1 --block 5 --rewrite
    .\scripts\raft-local.ps1 scenario              시연 순서 전체 자동 확인
    .\scripts\raft-local.ps1 stop

  PC 1대 = 팀 1개 (PC 3대 시연):
    .\scripts\raft-local.ps1 start --team A --host 192.168.0.11 --leaders A1=http://192.168.0.11:9001,B1=http://192.168.0.12:9001,C1=http://192.168.0.13:9001

  모든 명령: python scripts\raft_nodes.py --help
#>
$root = Split-Path -Parent $PSScriptRoot
$fixed = $args | ForEach-Object { if ($_ -eq '-fresh') { '--fresh' } elseif ($_ -eq '-patrol') { '--patrol' } else { $_ } }
& python (Join-Path $root 'scripts\raft_nodes.py') @fixed
exit $LASTEXITCODE
