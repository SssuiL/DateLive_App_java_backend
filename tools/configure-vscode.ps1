$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$repository=(Join-Path $root '.tools/m2').Replace('\','/')
$settingsPath=Join-Path $root '.tools/vscode-maven-settings.xml'
$escaped=[Security.SecurityElement]::Escape($repository)
$xml='<?xml version="1.0" encoding="UTF-8"?>'+[Environment]::NewLine+'<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"><localRepository>'+$escaped+'</localRepository></settings>'+[Environment]::NewLine
[IO.File]::WriteAllText($settingsPath,$xml,[Text.UTF8Encoding]::new($false))
$workspacePath=Join-Path $root '.vscode/settings.json'
$config=Get-Content -Raw $workspacePath|ConvertFrom-Json -AsHashtable
# Oracle 26.0.2 does not include maven.userSettings in its workspace-variable resolvers.
$config['jdk.maven.userSettings']=$settingsPath.Replace('\','/')
[IO.File]::WriteAllText($workspacePath,($config|ConvertTo-Json -Depth 12)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
Write-Output 'VS Code Maven settings now use this project dependency repository.'
