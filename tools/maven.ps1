param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs = @('verify')
)
$ErrorActionPreference = 'Stop'
$javaProjectRoot = Split-Path $PSScriptRoot -Parent
$javaProjectJdk = Join-Path $javaProjectRoot '.tools/jdk/jdk-25.0.4.1+1'
if (-not (Test-Path (Join-Path $javaProjectJdk 'bin/javac.exe'))) {
    throw 'Project JDK 25 is missing. See docs/编译环境.md.'
}
$previousJavaHome = $env:JAVA_HOME
$previousMavenHome = $env:MAVEN_USER_HOME
$previousBuildPath = $env:PATH
$previousAudioEncoder=$env:JAVA_MEDIA_FFMPEG
$previousAudioProbe=$env:JAVA_MEDIA_FFPROBE
Push-Location $javaProjectRoot
try {
    $audioManifest=Join-Path $javaProjectRoot '.tools/ffmpeg-install.json'
    if(Test-Path $audioManifest){
      $audio=Get-Content -Raw $audioManifest|ConvertFrom-Json
      $env:JAVA_MEDIA_FFMPEG=$audio.ffmpeg
      $env:JAVA_MEDIA_FFPROBE=$audio.ffprobe
    }
    $env:JAVA_HOME = $javaProjectJdk
    $env:MAVEN_USER_HOME = Join-Path $javaProjectRoot '.tools/maven-home'
    $env:PATH = "$javaProjectJdk\bin;$previousBuildPath"
    & (Join-Path $javaProjectRoot 'mvnw.cmd') @MavenArgs
    $buildExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:MAVEN_USER_HOME = $previousMavenHome
    $env:PATH = $previousBuildPath
    $env:JAVA_MEDIA_FFMPEG=$previousAudioEncoder
    $env:JAVA_MEDIA_FFPROBE=$previousAudioProbe
    Pop-Location
}
exit $buildExitCode
