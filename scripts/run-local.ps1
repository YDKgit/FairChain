# 로컬 전체 실행: Redis(없으면 시연용 대체 서버) · 노드 · FairChain · 예제서버.
# Redis · FairChain · 예제서버는 창 하나씩, 노드는 scripts\raft_nodes.py가 창 없이 띄운다 (macOS의 run-local.sh와 같게).
#
#   .\scripts\run-local.ps1              빌드 후 실행 (노드: 리더 층 A1 · B1 · C1)
#   .\scripts\run-local.ps1 -NoBuild     빌드 없이 실행
#   .\scripts\run-local.ps1 -Members 2   팀마다 팀원 노드 2대를 더 띄움 (기본 0)
#   .\scripts\run-local.ps1 -Fresh       노드 장부 · Raft 상태 · 키를 지우고 새로 시작 (기본은 이어서)
#   .\scripts\run-local.ps1 -Mem         시연 · 전체 테스트용: FairChain · 예제서버 DB를 메모리에, 노드 장부도 새로, 계정은 시연용 고정값
#                                        (operator1234 · staff1234 · admin1234 — scripts\seed-*.py와 macOS의 run-local.sh --mem이 쓰는 값).
#                                        끄면 데이터가 사라지고, data\ 의 예전 DB 파일은 건드리지 않는다
#   .\scripts\run-local.ps1 -NoQueue     예제서버가 대기열을 거치지 않음
#   .\scripts\run-local.ps1 -MySql       FairChain DB를 MySQL로 (application-local.yml 필요), 기본은 H2 파일
#   .\scripts\run-local.ps1 -NoBrowser   브라우저를 열지 않음
#   .\scripts\stop-local.ps1             전부 종료 (창도 닫음)
#
# 띄운 뒤 노드는 같은 도구로 다룬다 (장부 · 로그는 data\raft):
#   python scripts\raft_nodes.py status
#   python scripts\raft_nodes.py new-node D1 --team D --port 9004   새 노드를 켠 뒤, 점검 화면 → 블록체인 노드 → 노드 추가에 http://localhost:9004
#
# 개발용 계정 · 키는 처음 실행할 때 만들어 data\local-secrets.json에 저장한다 (data\는 git에 올라가지 않음).
param([switch]$NoBuild, [switch]$NoQueue, [switch]$MySql, [switch]$NoBrowser, [int]$Members = 0, [switch]$Fresh, [switch]$Mem)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Test-Port([int]$port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { return $client.ConnectAsync('127.0.0.1', $port).Wait(300) } catch { return $false } finally { $client.Dispose() }
}

function Wait-Port([int]$port, [string]$name, [int]$seconds = 120) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { Write-Host "  $name 준비됨 (:$port)"; return }
        Start-Sleep -Milliseconds 500
    }
    throw "$name 이(가) $seconds 초 안에 시작되지 않았습니다. 해당 창의 로그를 확인하세요."
}

function Start-Window([string]$title, [string]$command) {
    # FAIRCHAIN-LOCAL 표시는 stop-local.ps1이 이 창들을 찾아 닫는 데 쓴다
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "`$Host.UI.RawUI.WindowTitle = '$title'; <# FAIRCHAIN-LOCAL #> Set-Location '$root'; $command"
}

foreach ($port in 9001, 9002, 9003, 8081, 8080) {
    if (Test-Port $port) { throw "포트 $port 이(가) 이미 사용 중입니다. .\scripts\stop-local.ps1 로 이전 실행을 종료하세요." }
}

if (-not $NoBuild) {
    & .\gradlew.bat :node:bootJar :fairchain:bootJar :example-server:bootJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw '빌드 실패' }
}

$data = Join-Path $root 'data'
New-Item -ItemType Directory -Force $data | Out-Null
$secretsFile = Join-Path $data 'local-secrets.json'
if ($Mem) {
    # seed 스크립트(scripts\seed-*.py)와 macOS의 run-local.sh가 쓰는 시연용 고정값. 밖에서 접속되는 곳에 띄울 때는 쓰지 말 것
    $s = [pscustomobject]@{
        nodeSecret        = 'local-demo-change-me'
        clientApiKey      = 'key-localdemo1234'
        fairchainAdminPw  = 'admin1234'
        exampleOperatorPw = 'operator1234'
        staffPw           = 'staff1234'
    }
} elseif (Test-Path $secretsFile) {
    $s = Get-Content $secretsFile -Raw | ConvertFrom-Json
} else {
    $rand = { -join ((48..57) + (97..122) | Get-Random -Count 14 | ForEach-Object { [char]$_ }) }
    $s = [pscustomobject]@{
        nodeSecret        = "node-$(& $rand)"
        clientApiKey      = "key-$(& $rand)"
        fairchainAdminPw  = (& $rand)
        exampleOperatorPw = (& $rand)
        staffPw           = (& $rand)
    }
    $s | ConvertTo-Json | Set-Content -Encoding utf8 $secretsFile
}

Write-Host '[1/4] Redis'
if (Test-Port 6379) {
    Write-Host '  이미 실행 중인 Redis를 사용합니다 (:6379)'
} else {
    Write-Host '  Redis가 없어 시연용 대체 서버를 띄웁니다 (scripts\dev-redis.py)'
    Start-Window 'FairChain Redis (dev)' 'python scripts\dev-redis.py 6379'
    Wait-Port 6379 'Redis'
}

Write-Host "[2/4] 노드 (리더 층 3대 + 팀마다 팀원 ${Members}대)"
# 노드는 raft_nodes.py로 띄운다: 장부 · Raft 상태 · 키는 data\raft\<ID>*, 로그는 data\raft\<ID>.log.
# 예전 방식의 장부(data\node1~3)는 쓰지 않는다 — 서명이 없는 예전 장부로 켜면 노드가 켤 때 검사에서 바로 빠진다.
$nodeArgs = @('scripts\raft_nodes.py', 'start', '--members', "$Members", '--secret', $s.nodeSecret)
if ($Fresh -or $Mem) { $nodeArgs += '--fresh' }
$env:PYTHONIOENCODING = 'utf-8'
& python @nodeArgs | Select-Object -Last ($Members * 3 + 4)
if ($LASTEXITCODE -ne 0) { throw '노드를 시작하지 못했습니다. data\raft\<ID>.log 를 확인하세요' }
foreach ($port in 9001, 9002, 9003) { Wait-Port $port "Node :$port" }
# 이전 구성을 이어서 켠 경우 노드는 그 구성의 비밀값을 쓴다 — FairChain도 같은 값을 써야 한다
$nodeSecret = (Get-Content (Join-Path $data 'raft\layout.json') -Raw -Encoding UTF8 | ConvertFrom-Json).secret

Write-Host '[3/4] FairChain'
$db = if ($Mem) { "`$env:DB_URL='jdbc:h2:mem:fairchain;MODE=MySQL;DB_CLOSE_DELAY=-1'; `$env:DB_USERNAME='sa'; `$env:DB_PASSWORD=''; " }
      elseif ($MySql) { '' }
      else { "`$env:DB_URL='jdbc:h2:file:$data\fairchain;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE'; `$env:DB_USERNAME='sa'; `$env:DB_PASSWORD=''; " }
Start-Window 'FairChain (:8081)' ("$db java -jar fairchain\build\libs\fairchain-0.0.1-SNAPSHOT.jar " +
    "--fairchain.nodes.api-key=$nodeSecret --fairchain.seed.client-id=example-server --fairchain.seed.api-key=$($s.clientApiKey) " +
    "--fairchain.seed.operator-login-id=admin --fairchain.seed.operator-password=$($s.fairchainAdminPw)")
Wait-Port 8081 'FairChain'

Write-Host '[4/4] 예제서버'
$queue = if ($NoQueue) { 'false' } else { 'true' }
$exampleDb = if ($Mem) { "`$env:EXAMPLE_DB_URL='jdbc:h2:mem:example;MODE=MySQL;DB_CLOSE_DELAY=-1'; " } else { '' }
Start-Window 'Example server (:8080)' ("$exampleDb`$env:FAIRCHAIN_URL='http://localhost:8081'; `$env:FAIRCHAIN_API_KEY='$($s.clientApiKey)'; " +
    "`$env:EXAMPLE_OPERATOR_PASSWORD='$($s.exampleOperatorPw)'; `$env:EXAMPLE_STAFF_PASSWORD='$($s.staffPw)'; " +
    "`$env:EXAMPLE_QUEUE_ENABLED='$queue'; java -jar example-server\build\libs\example-server-0.0.1-SNAPSHOT.jar")
Wait-Port 8080 '예제서버'

Write-Host ''
Write-Host '실행 완료'
Write-Host '  예제 예매 사이트     http://localhost:8080'
Write-Host "    운영자            operator / $($s.exampleOperatorPw)"
Write-Host "    게이트 스태프      staff / $($s.staffPw)"
Write-Host '  FairChain 점검 화면  http://localhost:8081/inspector.html'
Write-Host "    FairChain 운영자   admin / $($s.fairchainAdminPw)"
if ($Mem) { Write-Host '  (-Mem: 시연용 고정 계정 · DB는 메모리 — 끄면 사라집니다. 시연 데이터 채우기: python scripts\seed-seats.py 2 20 30 등, scripts\seed-demo.sh 참고)' }
else { Write-Host '  (계정은 data\local-secrets.json 에 저장되어 있습니다)' }
Write-Host '  노드 상태           python scripts\raft_nodes.py status'
Write-Host '  노드 추가           python scripts\raft_nodes.py new-node D1 --team D --port 9004  → 점검 화면 → 블록체인 노드 → 노드 추가에 http://localhost:9004'
if (-not $NoBrowser) {
    Start-Process 'http://localhost:8080'
    Start-Process 'http://localhost:8081/inspector.html'
}
