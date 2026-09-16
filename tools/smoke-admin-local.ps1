$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
$javaLocal=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
if(-not $javaLocal.adminPassword){throw 'Start with tools/start-local.ps1 -DevelopmentAdmin first'}
function Invoke-AdminApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 Invoke-RestMethod @requestArgs
}
$owner=$null
for($attempt=0;$attempt -lt 5;$attempt++){
 try{$owner=Invoke-AdminApi POST '/admin/auth/login' @{username=$javaLocal.adminUsername;password=$javaLocal.adminPassword};break}
 catch{if([int]$_.Exception.Response.StatusCode -ne 401 -or $attempt -eq 4){throw};Start-Sleep -Milliseconds 250}
}
$auditName='audit_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$auditPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$auditor=Invoke-AdminApi POST '/admin/admin-users' @{username=$auditName;password=$auditPassword;display_name='本地审核冒烟';role='auditor'} $owner.access_token
$auditLogin=Invoke-AdminApi POST '/admin/auth/login' @{username=$auditName;password=$auditPassword}
$phone='195'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
$user=Invoke-AdminApi POST '/auth/register' @{phone=$phone;password=('Admin-media-'+[Guid]::NewGuid().ToString('N'));nickname='审核测试图片'}
Add-Type -AssemblyName System.Drawing
$imagePath=Join-Path $javaProjectRoot '.tools/admin-smoke.png'
$bitmap=[Drawing.Bitmap]::new(3,2)
try{$bitmap.Save($imagePath,[Drawing.Imaging.ImageFormat]::Png)}finally{$bitmap.Dispose()}
$client=[Net.Http.HttpClient]::new()
$form=[Net.Http.MultipartFormDataContent]::new()
$imageContent=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($imagePath))
$imageContent.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
$form.Add([Net.Http.StringContent]::new('image'),'media_type')
$form.Add([Net.Http.StringContent]::new('profile'),'source')
$form.Add($imageContent,'file','admin-smoke.png')
$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$user.access_token)
try{
 $response=$client.PostAsync(($javaBase+'/media/upload'),$form).GetAwaiter().GetResult()
 try{
  if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)}
  $asset=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
 }finally{$response.Dispose()}
}finally{$form.Dispose();$client.Dispose()}
$null=Invoke-AdminApi PATCH '/profiles/me' @{avatar_url=$asset.url} $user.access_token
$pending=Invoke-AdminApi GET '/admin/moderation/media/pending' $null $auditLogin.access_token
if(-not (@($pending)|Where-Object {$_.id -eq $asset.id})){throw 'Pending media not visible to reviewer'}
$preview=Invoke-AdminApi POST ("/admin/moderation/media/"+$asset.id+"/preview-url") $null $auditLogin.access_token
$null=Invoke-WebRequest ($javaBase+$preview.url) -TimeoutSec 15
$approved=Invoke-AdminApi POST ("/admin/moderation/media/"+$asset.id+"/approve") @{reason='合成测试图片通过';reviewer_id='must-not-be-trusted'} $auditLogin.access_token
if($approved.status -ne 'approved' -or $approved.reviewed_by -ne $auditor.id){throw 'Review actor or status mismatch'}
$profile=Invoke-AdminApi GET '/profiles/me' $null $user.access_token
if(-not $profile.avatar_url -or $profile.pending_avatar_url){throw 'Approved avatar was not promoted'}
$null=Invoke-WebRequest ($javaBase+$profile.avatar_url) -TimeoutSec 15
$rejected=Invoke-AdminApi POST ("/admin/moderation/media/"+$asset.id+"/reject") @{reason='测试撤销审核'} $auditLogin.access_token
if($rejected.status -ne 'rejected'){throw 'Rejection failed'}
$profile=Invoke-AdminApi GET '/profiles/me' $null $user.access_token
if($profile.avatar_url){throw 'Rejected avatar remains public'}
$logs=Invoke-AdminApi GET ("/admin/operation-logs?target_id="+$asset.id) $null $owner.access_token
if(@($logs).Count -lt 3){throw 'Review/preview audit missing'}
$null=Invoke-AdminApi PATCH ("/admin/admin-users/"+$auditor.id+"/status") @{status='disabled';reason='本地冒烟结束'} $owner.access_token
try{$null=Invoke-WebRequest ($javaBase+$preview.url) -TimeoutSec 15;throw 'Disabled reviewer preview still works'}
catch{if([int]$_.Exception.Response.StatusCode -ne 403){throw}}
$null=Invoke-AdminApi DELETE ("/media/"+$asset.id) $null $user.access_token
$null=Invoke-AdminApi POST '/auth/logout' @{refresh_token=$user.refresh_token}
$null=Invoke-AdminApi POST '/admin/auth/logout' $null $owner.access_token
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('owner_login','create_auditor','auditor_login','pending_list','admin_preview','approve','trusted_reviewer_identity','avatar_promotion','reject','audit_logs','disable_reviewer_revokes_preview','delete_fixture','logout')
 note='Synthetic account/image only. Temporary auditor disabled; owner credentials remain in ignored local configuration. No external moderation or production deployment.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-admin-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
