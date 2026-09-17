$ErrorActionPreference='Stop'
$workerRoot=Split-Path $PSScriptRoot -Parent
$workerPidPath=Join-Path $workerRoot '.tools/run/media-worker.pid'
if(-not (Test-Path -LiteralPath $workerPidPath)){Write-Host 'No media worker PID recorded.';exit 0}
$recorded=[int](Get-Content -LiteralPath $workerPidPath)
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$recorded"
if($process){
 $runtime=[IO.Path]::GetFullPath((Join-Path $workerRoot '.tools/jdk/jdk-25.0.4.1+1/bin/java.exe'))
 $jar=[IO.Path]::GetFullPath((Join-Path $workerRoot '.tools/run/media-worker.jar'))
 if($process.ExecutablePath -ne $runtime -or -not $process.CommandLine.Contains($jar)){throw 'Worker identity mismatch; refusing to stop it'}
 $handle=[Diagnostics.Process]::GetProcessById($recorded);$handle.Kill($true);$handle.WaitForExit(10000)|Out-Null;$handle.Dispose()
}
Remove-Item -LiteralPath $workerPidPath
Write-Host 'Media worker stopped; queued jobs and database retained.'
