$ErrorActionPreference='Stop'
$javaApi='http://127.0.0.1:8200'
$javaSession=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$javaSamples=[Collections.Generic.List[double]]::new()
$javaPassword=[Guid]::NewGuid().ToString('N')+'-Java9!'
$javaRegistration=$null
for($sample=0;$sample -lt 10;$sample++) {
 $javaPhone='199'+([Security.Cryptography.RandomNumberGenerator]::GetInt32(0,100000000)).ToString('D8')
 $javaBody=@{phone=$javaPhone;password=$javaPassword;nickname='Java本地验收'}|ConvertTo-Json -Compress
 $javaTimer=[Diagnostics.Stopwatch]::StartNew()
 $javaRegistration=Invoke-RestMethod -Uri "$javaApi/auth/register" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($javaBody)) -WebSession $javaSession -TimeoutSec 15
 $javaTimer.Stop()
 $javaSamples.Add([Math]::Round($javaTimer.Elapsed.TotalMilliseconds,2))
}
$javaHeaders=@{Authorization='Bearer '+$javaRegistration.access_token}
$javaUser=Invoke-RestMethod "$javaApi/auth/me" -Headers $javaHeaders -WebSession $javaSession -TimeoutSec 5
if($javaUser.id -ne $javaRegistration.user_id){throw 'Current-user identity mismatch'}
$javaLoginBody=@{phone=$javaPhone;password=$javaPassword}|ConvertTo-Json -Compress
$javaLogin=Invoke-RestMethod "$javaApi/auth/login" -Method Post -ContentType 'application/json' -Body $javaLoginBody -WebSession $javaSession -TimeoutSec 10
$javaRefreshBody=@{refresh_token=$javaLogin.refresh_token}|ConvertTo-Json -Compress
$javaRefreshed=Invoke-RestMethod "$javaApi/auth/refresh" -Method Post -ContentType 'application/json' -Body $javaRefreshBody -WebSession $javaSession -TimeoutSec 5
$javaLogoutBody=@{refresh_token=$javaRefreshed.refresh_token}|ConvertTo-Json -Compress
$null=Invoke-RestMethod "$javaApi/auth/logout" -Method Post -ContentType 'application/json' -Body $javaLogoutBody -WebSession $javaSession -TimeoutSec 5
$javaSorted=@($javaSamples|Sort-Object)
$javaReport=[ordered]@{
 checkedAt=[DateTime]::UtcNow.ToString('o')
 baseUrl=$javaApi
 scope='10 sequential local registrations; not mobile, cloud or capacity testing'
 sampleCount=$javaSamples.Count
 registrationMs=@($javaSamples)
 medianMs=($javaSorted[4]+$javaSorted[5])/2
 p95Ms=$javaSorted[9]
 maxMs=$javaSorted[9]
 loginRefreshLogout='passed'
}
$javaOutput=Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/local-auth-smoke.json'
[IO.File]::WriteAllText($javaOutput,($javaReport|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
$javaReport|ConvertTo-Json -Depth 4
