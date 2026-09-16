$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$source='https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
$expected='fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9'
$archive=Join-Path $javaProjectRoot '.tools/ffmpeg-essentials.zip'
if(-not (Test-Path $archive)){Invoke-WebRequest $source -OutFile $archive}
if((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'Expected FFmpeg 9.0.1 checksum. Do not execute changed downloads; update the pinned version after review.'}
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $javaProjectRoot '.tools/ffmpeg') -Force
$binary=Join-Path $javaProjectRoot '.tools/ffmpeg/ffmpeg-9.0.1-essentials_build/bin/ffmpeg.exe'
$probe=Join-Path (Split-Path $binary) 'ffprobe.exe'
if(-not (Test-Path $binary) -or -not (Test-Path $probe)){throw 'FFmpeg archive layout mismatch'}
$manifest=@{source=$source;sha256=$expected;ffmpeg=$binary;ffprobe=$probe;installed_at=[DateTime]::UtcNow.ToString('o')}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot '.tools/ffmpeg-install.json'),($manifest|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
& $binary -version | Select-Object -First 1
