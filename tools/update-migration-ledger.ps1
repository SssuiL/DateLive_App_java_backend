$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$legacy=Get-Content -Raw (Join-Path $root 'docs/legacy-inventory.json')|ConvertFrom-Json
$current=Get-Content -Raw (Join-Path $root 'docs/migration-status.json')|ConvertFrom-Json
function Route-Key($method,$path){$method.ToUpperInvariant()+' '+(($path -replace '\{[^}]+\}','{}').TrimEnd('/'))}
$implemented=@{};foreach($route in $current.routes){$implemented[(Route-Key $route.method $route.path)]=$route}
$items=@(foreach($route in $legacy.routes){
 $key=Route-Key $route.method $route.path;$match=$implemented[$key]
 [ordered]@{method=$route.method;legacy_path=$route.path;source=$route.source;java_path=$(if($match){$match.path}else{$null});status=$(if($match){'route_mapped_contract_audit_required'}else{'not_mapped'});note='Route presence alone is not functional parity. Compare schemas, permissions, state transitions, events and failure cases before closing.'}
})
$report=[ordered]@{legacy_commit=$legacy.summary.legacy_commit;legacy_http_routes=$legacy.summary.http_routes;legacy_websocket_routes=$legacy.summary.websocket_routes;java_verified_tests=$current.verified_tests;mapped_routes=@($items|Where-Object status -eq 'route_mapped_contract_audit_required').Count;unmapped_routes=@($items|Where-Object status -eq 'not_mapped').Count;scope='Static route cross-reference; excludes Java-only additions and does not claim full semantic or supplier parity.';routes=$items}
[IO.File]::WriteAllText((Join-Path $root 'docs/全量迁移核对表.json'),($report|ConvertTo-Json -Depth 8)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
Write-Output ('Legacy routes mapped: '+$report.mapped_routes+'; not mapped: '+$report.unmapped_routes)
