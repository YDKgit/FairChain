# run-local.ps1로 띄운 노드 · FairChain · 예제서버 · 시연용 Redis와 그 창들을 종료한다
$targets = Get-CimInstance Win32_Process | Where-Object {
    (($_.Name -match '^java') -and ($_.CommandLine -match '(node|fairchain|example-server)-0\.0\.1-SNAPSHOT\.jar')) -or
    (($_.Name -match '^python') -and ($_.CommandLine -match 'dev-redis\.py')) -or
    (($_.Name -match '^powershell') -and ($_.CommandLine -match 'FAIRCHAIN-LOCAL'))
}
foreach ($p in $targets) {
    Write-Host "종료: $($p.ProcessId) $($p.Name)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if (-not $targets) { Write-Host '실행 중인 서버가 없습니다' }
