param([string]$BaseUrl="http://localhost:8080",[string]$ApiKey="demo-key",[Parameter(Mandatory=$true)][string]$RecordKey)
$headers=@{ "X-API-Key"=$ApiKey }
try {
  $result=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/anchors/tickets/$RecordKey/reconcile" -Headers $headers
  $result | ConvertTo-Json -Depth 6
  if(-not $result.valid) { exit 1 }
} catch { Write-Error $_; exit 2 }
