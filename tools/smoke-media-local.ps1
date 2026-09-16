$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-MediaApi($method,$path,$body=$null,$token=$null) {
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 Invoke-RestMethod @requestArgs
}
$null=Invoke-MediaApi GET '/health/ready'
$phone='196'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
$user=Invoke-MediaApi POST '/auth/register' @{phone=$phone;password=('Media-'+[Guid]::NewGuid().ToString('N'));nickname='图片冒烟'}
$token=$user.access_token
Add-Type -AssemblyName System.Drawing
$bitmap=[Drawing.Bitmap]::new(3,2)
$imagePath=Join-Path $javaProjectRoot '.tools/media-smoke.png'
try{$bitmap.Save($imagePath,[Drawing.Imaging.ImageFormat]::Png)}finally{$bitmap.Dispose()}
$client=[Net.Http.HttpClient]::new()
$form=[Net.Http.MultipartFormDataContent]::new()
$imageContent=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($imagePath))
$imageContent.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
$form.Add([Net.Http.StringContent]::new('image'),'media_type')
$form.Add([Net.Http.StringContent]::new('profile'),'source')
$form.Add($imageContent,'file','media-smoke.png')
$client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
try{
 $response=$client.PostAsync(($javaBase+'/media/upload'),$form).GetAwaiter().GetResult()
 try{
  if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)}
  $asset=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
 }finally{$response.Dispose()}
}finally{$form.Dispose();$client.Dispose()}
if($asset.status -ne 'review_pending' -or $asset.content_type -ne 'image/png'){throw 'Unexpected upload result'}
$read=Invoke-WebRequest ($javaBase+$asset.preview_url) -TimeoutSec 15
if($read.StatusCode -ne 200){throw 'Owner preview failed'}
$profile=Invoke-MediaApi PATCH '/profiles/me' @{avatar_url=$asset.url;photo_urls=@($asset.url)} $token
if($profile.avatar_url -or -not $profile.pending_avatar_url){throw 'Pending image was not isolated'}
$null=Invoke-WebRequest ($javaBase+$profile.pending_avatar_url) -TimeoutSec 15
$list=Invoke-MediaApi GET '/media/me' $null $token
if(@($list).Count -ne 1){throw 'Media list mismatch'}
$grant=Invoke-MediaApi POST ("/media/"+$asset.id+"/access-url") $null $token
$null=Invoke-WebRequest ($javaBase+$grant.url) -TimeoutSec 15
$null=Invoke-MediaApi DELETE ("/media/"+$asset.id) $null $token
try{$null=Invoke-WebRequest ($javaBase+$grant.url) -TimeoutSec 15;throw 'Deleted media still accessible'}
catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
$profile=Invoke-MediaApi GET '/profiles/me' $null $token
if($profile.pending_avatar_url -or @($profile.pending_photo_urls).Count -ne 0){throw 'Deleted reference remains in profile'}
$null=Invoke-MediaApi POST '/auth/logout' @{refresh_token=$user.refresh_token}
$report=@{
 checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;result='passed'
 checks=@('multipart_upload','normalized_png','owner_preview','pending_profile_isolation','media_list','signed_access','delete','deleted_url_revoked','profile_reference_removed','logout')
 note='Synthetic local image and account; no real moderation provider or cloud storage. Review transitions tested through internal service in isolated tests.'
}
[IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-media-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 5
