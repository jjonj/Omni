# End-to-end live Gateway smoke; isolated ephemeral tool/server only.
$ErrorActionPreference='Stop'
$port = 59644
$slug = 'gateway-smoke-temporary'
$folder = Join-Path $env:TEMP ('omnihub-gateway-smoke-'+[Guid]::NewGuid().ToString('N'))
$gateway = 'http://127.0.0.1:3333'
$lite = 'http://127.0.0.1:8771/mcp'
if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Test port $port occupied" }
New-Item -ItemType Directory -Force $folder | Out-Null
Set-Content -LiteralPath (Join-Path $folder 'index.html') -Encoding utf8 -Value '<!doctype html><title>OmniHub Gateway Smoke</title><p>Ready</p>'
function Invoke-Lite([string]$name,[hashtable]$arguments) {
  $body=@{jsonrpc='2.0';id=1;method='tools/call';params=@{name=$name;arguments=$arguments}}|ConvertTo-Json -Depth 12 -Compress
  $result=Invoke-RestMethod $lite -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 8
  if($result.result.isError) { throw ($result.result.content[0].text) }
  return $result.result.content[0].text
}
try {
  $tool=@{
    id=$slug;name='Gateway Temporary Smoke';description='Ephemeral Gateway integration test'
    working_directory=$folder;executable='py';arguments=@('-m','http.server',"$port",'--bind','127.0.0.1')
    url="http://127.0.0.1:$port/";checks=@(@{url="http://127.0.0.1:$port/";contains='<title>OmniHub Gateway Smoke</title>'})
    startup_timeout_seconds=15
  }
  Invoke-Lite 'register_web_tool' $tool | Write-Output
  $pre=Invoke-RestMethod "$gateway/gateway/api/tools" -TimeoutSec 5
  $row=@($pre|Where-Object id -eq $slug)
  if($row.Count -ne 1 -or $row[0].status -ne 'stopped'){throw 'Tool was not registered in stopped state'}
  $redirect=& curl.exe -sS -o NUL -w '%{http_code} %{redirect_url}' "$gateway/$slug"
  if($redirect -notmatch '302 .*gateway/launch.html') {throw "Direct route failed: $redirect"}
  $launch=Invoke-RestMethod "$gateway/gateway/api/launch/$slug" -Method Post -TimeoutSec 30
  if($launch.url -ne "http://127.0.0.1:$port/"){throw 'Launch did not return redirect URL'}
  $html=(Invoke-WebRequest "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 5).Content
  if($html -notmatch 'OmniHub Gateway Smoke'){throw 'HTTP server did not serve the expected content'}
  $post=Invoke-RestMethod "$gateway/gateway/api/tools" -TimeoutSec 5
  if((@($post|Where-Object id -eq $slug))[0].status -ne 'running'){throw 'Gateway did not report running'}
  Write-Output "PASS: registered, direct route, on-demand launch, HTTP response and running status"
}
finally {
  try { Invoke-Lite 'unregister_web_tool' @{id=$slug} | Write-Output } catch { Write-Warning "Cleanup unregister: $_" }
  $owner=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if($owner) {
     $proc=Get-CimInstance Win32_Process -Filter ("ProcessId="+$owner.OwningProcess)
     if(($proc.CommandLine -match 'http.server') -and ($proc.CommandLine -match "$port")) {
       Stop-Process -Id $owner.OwningProcess -Force -ErrorAction SilentlyContinue
     } else { Write-Warning "Not terminating unknown port owner $($owner.OwningProcess)" }
  }
  Remove-Item $folder -Recurse -Force -ErrorAction SilentlyContinue
}
