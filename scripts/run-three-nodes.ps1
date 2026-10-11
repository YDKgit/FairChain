# Run from project root after .\gradlew.bat :node:bootJar
# Opens three PowerShell windows, each with an independent database.
$jar = (Resolve-Path '.\node\build\libs\node-*.jar').Path
$nodes = @(
 @{port='8081';db='./data/node1';peers='http://localhost:8082,http://localhost:8083'},
 @{port='8082';db='./data/node2';peers='http://localhost:8081,http://localhost:8083'},
 @{port='8083';db='./data/node3';peers='http://localhost:8081,http://localhost:8082'}
)
foreach ($n in $nodes) {
 $command = "`$env:NODE_PORT='$($n.port)'; `$env:NODE_DB='$($n.db)'; `$env:NODE_PEERS='$($n.peers)'; java -jar '$jar'"
 Start-Process powershell -ArgumentList '-NoExit','-Command',$command
}
