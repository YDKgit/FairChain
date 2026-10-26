# 로컬 전체 실행: Redis(없으면 시연용 대체 서버) · 노드 3대 · FairChain · 예제서버 — 각각 창 하나씩
#
#   .\scripts\run-local.ps1              빌드 후 실행
#   .\scripts\run-local.ps1 -NoBuild     빌드 없이 실행
#   .\scripts\run-local.ps1 -NoQueue     예제서버가 대기열을 거치지 않음
#   .\scripts\run-local.ps1 -MySql       FairChain DB를 MySQL로 (application-local.yml 필요), 기본은 H2 파일
#   .\scripts\run-local.ps1 -NoBrowser   브라우저를 열지 않음
#   .\scripts\stop-local.ps1             전부 종료 (창도 닫음)
#
# 개발용 계정 · 키는 처음 실행할 때 만들어 data\local-secrets.json에 저장한다 (data\는 git에 올라가지 않음).
param([switch]$NoBuild, [switch]$NoQueue, [switch]$MySql, [switch]$NoBrowser)
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
if (Test-Path $secretsFile) {
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

Write-Host '[2/4] 노드 3대'
foreach ($i in 1, 2, 3) {
    $port = 9000 + $i
    Start-Window "Node $i (:$port)" "`$env:NODE_PORT='$port'; `$env:NODE_DB='$data\node$i'; `$env:NODE_SECRET='$($s.nodeSecret)'; java -jar node\build\libs\node-0.0.1-SNAPSHOT.jar"
}
foreach ($i in 1, 2, 3) { Wait-Port (9000 + $i) "Node $i" }

Write-Host '[3/4] FairChain'
$db = if ($MySql) { '' } else { "`$env:DB_URL='jdbc:h2:file:$data\fairchain;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE'; `$env:DB_USERNAME='sa'; `$env:DB_PASSWORD=''; " }
Start-Window 'FairChain (:8081)' ("$db java -jar fairchain\build\libs\fairchain-0.0.1-SNAPSHOT.jar " +
    "--fairchain.nodes.api-key=$($s.nodeSecret) --fairchain.seed.client-id=example-server --fairchain.seed.api-key=$($s.clientApiKey) " +
    "--fairchain.seed.operator-login-id=admin --fairchain.seed.operator-password=$($s.fairchainAdminPw)")
Wait-Port 8081 'FairChain'

Write-Host '[4/4] 예제서버'
$queue = if ($NoQueue) { 'false' } else { 'true' }
Start-Window 'Example server (:8080)' ("`$env:FAIRCHAIN_URL='http://localhost:8081'; `$env:FAIRCHAIN_API_KEY='$($s.clientApiKey)'; " +
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
Write-Host '  (계정은 data\local-secrets.json 에 저장되어 있습니다)'
if (-not $NoBrowser) {
    Start-Process 'http://localhost:8080'
    Start-Process 'http://localhost:8081/inspector.html'
}
