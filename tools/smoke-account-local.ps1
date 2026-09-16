$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-AccountApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 Invoke-RestMethod @requestArgs
}
$null=Invoke-AccountApi GET '/health/ready'
$phone='194'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
$user=Invoke-AccountApi POST '/auth/register' @{phone=$phone;password=('Account-'+[Guid]::NewGuid().ToString('N'));nickname='注销恢复测试'}
$requested=Invoke-AccountApi POST '/auth/account/deactivate' $null $user.access_token
$again=Invoke-AccountApi POST '/auth/account/deactivate' $null $user.access_token
if($requested.cooling_off_days -ne 45 -or $requested.deactivation_due_at -ne $again.deactivation_due_at){throw 'Cooling period or idempotency mismatch'}
$javaLocal=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
$owner=Invoke-AccountApi POST '/admin/auth/login' @{username=$javaLocal.adminUsername;password=$javaLocal.adminPassword}
$preview=Invoke-AccountApi GET ("/admin/users/"+$user.user_id+"/erasure-preview") $null $owner.access_token
if($preview.eligible -or $preview.counts.profiles -ne 1){throw 'Preview must be ineligible and preserve profile'}
$record=Invoke-AccountApi GET ("/admin/users/"+$user.user_id+"/erasure") $null $owner.access_token
if($record.status -ne 'pending'){throw 'Pending record missing'}
$restored=Invoke-AccountApi POST '/auth/account/restore' $null $user.access_token
if($restored.status -ne 'active' -or $restored.deactivation_due_at){throw 'Restore failed'}
$profile=Invoke-AccountApi GET '/profiles/me' $null $user.access_token
if($profile.nickname -ne '注销恢复测试'){throw 'Profile data changed'}
$null=Invoke-AccountApi POST '/auth/logout' @{refresh_token=$user.refresh_token}
$null=Invoke-AccountApi POST '/admin/auth/logout' $null $owner.access_token
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 local_data_erased=$false
 checks=@('request_deactivation','45_day_cooling_period','idempotent_request','owner_preview','pending_record','restore','profile_preserved','logout')
 note='Only a synthetic local account was requested/restored. Due erasure and file deletion tested exclusively in isolated Testcontainers tests.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-account-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
