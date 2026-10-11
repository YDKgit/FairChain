# Run from repository root after starting three Node applications.
# Read-only cluster check; does not modify ledgers.
param([string]$Secret = $env:NODE_SECRET)
if (-not $Secret) { $Secret = 'local-demo-change-me' }
$results = @()
foreach ($port in @(8081,8082,8083)) {
  try {
    $state = Invoke-RestMethod -Method GET -Uri "http://localhost:$port/node/ledger/status" -Headers @{ 'X-Node-Secret' = $Secret } -TimeoutSec 5
    $results += [pscustomobject]@{ Port = $port; Online = $true; Height = $state.height; HeadHash = $state.headHash; ChainValid = $state.chainValid; Pending = @($state.pendingIndices).Count }
  } catch {
    $results += [pscustomobject]@{ Port = $port; Online = $false; Height = $null; HeadHash = $null; ChainValid = $false; Pending = -1 }
  }
}
$results | Format-Table -AutoSize
$healthy = @($results | Where-Object { $_.Online -and $_.ChainValid -and $_.Pending -eq 0 })
$heads = @($results | Where-Object { $_.Online } | ForEach-Object { "$($_.Height):$($_.HeadHash)" } | Select-Object -Unique)
if ($healthy.Count -eq 3 -and $heads.Count -eq 1) {
  Write-Host 'CONSISTENT: all three nodes report the same valid head with no pending proposals.'
  exit 0
}
Write-Warning 'INCONSISTENT or INCOMPLETE: investigate pending proposals and node availability. No automatic repair was performed.'
exit 1
