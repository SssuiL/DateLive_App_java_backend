$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'stop-media-worker-local.ps1')
$javaPidFile=Join-Path $javaProjectRoot '.tools/run/api.pid'
if(-not (Test-Path $javaPidFile)){Write-Host 'No Java API PID recorded.';exit 0}
$javaRecordedPid=[int](Get-Content $javaPidFile)
$javaProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$javaRecordedPid"
if($javaProcess) {
 $javaExpectedRuntime=[IO.Path]::GetFullPath((Join-Path $javaProjectRoot '.tools/jdk/jdk-25.0.4.1+1/bin/java.exe'))
 $javaExpectedJar=[IO.Path]::GetFullPath((Join-Path $javaProjectRoot '.tools/run/backend.jar'))
 if($javaProcess.ExecutablePath -ne $javaExpectedRuntime -or -not $javaProcess.CommandLine.Contains($javaExpectedJar)){throw 'Process identity mismatch; refusing to stop it'}
 Stop-Process -Id $javaRecordedPid
}
Remove-Item -LiteralPath $javaPidFile
Write-Host 'Java API stopped; independent PostgreSQL data retained.'
