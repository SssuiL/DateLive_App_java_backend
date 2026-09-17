$ErrorActionPreference='Stop'
$javaProjectRoot=Split-Path $PSScriptRoot -Parent
$javaBase='http://127.0.0.1:8200'
function Invoke-RealtimeApi($method,$path,$body=$null,$token=$null){
 $requestArgs=@{Method=$method;Uri=($javaBase+$path);TimeoutSec=15}
 if($null -ne $body){$requestArgs.ContentType='application/json; charset=utf-8';$requestArgs.Body=($body|ConvertTo-Json -Depth 6 -Compress)}
 if($token){$requestArgs.Headers=@{Authorization="Bearer $token"}}
 $response=Invoke-RestMethod @requestArgs
 foreach($item in $response){$item}
}
function New-RealtimeUser{
 $phone='196'+[Security.Cryptography.RandomNumberGenerator]::GetInt32(10000000,99999999)
 Invoke-RealtimeApi POST '/auth/register' @{phone=$phone;password=('Realtime-'+[Guid]::NewGuid().ToString('N'));nickname='实时迁移测试'}
}

function Upload-DerivativeFixture($bytes,$mime,$conversation,$token){
 $client=[Net.Http.HttpClient]::new();$form=[Net.Http.MultipartFormDataContent]::new()
 $content=[Net.Http.ByteArrayContent]::new([byte[]]$bytes);$content.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new($mime)
 $form.Add([Net.Http.StringContent]::new('image'),'media_type');$form.Add([Net.Http.StringContent]::new('chat'),'source');$form.Add([Net.Http.StringContent]::new($conversation),'conversation_id');$form.Add($content,'file','synthetic-image')
 $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
 try{$response=$client.PostAsync(($javaBase+'/media/upload'),$form).GetAwaiter().GetResult()
  try{if(-not $response.IsSuccessStatusCode){throw ('Upload failed: '+[int]$response.StatusCode)};return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json)}
  finally{$response.Dispose()}
 }finally{$form.Dispose();$client.Dispose()}
}
$a=$null;$b=$null;$admin=$null
try{
 $null=Invoke-RealtimeApi GET '/health/ready'
 $local=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/local-development.json')|ConvertFrom-Json
 $admin=Invoke-RealtimeApi POST '/admin/auth/login' @{username=$local.adminUsername;password=$local.adminPassword}
 $a=New-RealtimeUser;$b=New-RealtimeUser
 $request=Invoke-RealtimeApi POST '/friends/requests' @{target_user_id=$b.user_id} $a.access_token
 $null=Invoke-RealtimeApi POST ("/friends/requests/"+$request.id+"/accept") $null $b.access_token
 $conversations=@(Invoke-RealtimeApi GET '/conversations/' $null $a.access_token);$conversation=$conversations[0].id;$path='/conversations/'+$conversation
 $media=Get-Content -Raw (Join-Path $javaProjectRoot '.tools/ffmpeg-install.json')|ConvertFrom-Json
 $pngPath=Join-Path $javaProjectRoot '.tools/derivative-smoke.png'
 $gifPath=Join-Path $javaProjectRoot '.tools/derivative-smoke.gif'
 & $media.ffmpeg -v error -nostdin -y -f lavfi -i 'testsrc=size=1600x900:rate=1' -frames:v 1 -threads 1 $pngPath
 if($LASTEXITCODE -ne 0){throw 'PNG fixture generation failed'}
 & $media.ffmpeg -v error -nostdin -y -f lavfi -i 'testsrc=size=64x48:rate=2' -t 1 -threads 1 $gifPath
 if($LASTEXITCODE -ne 0){throw 'GIF fixture generation failed'}
 foreach($fixture in @(@{file=$pngPath;mime='image/png';kind='image';width=320;display=1280},@{file=$gifPath;mime='image/gif';kind='gif';width=64;display=64})){
  $asset=Upload-DerivativeFixture ([IO.File]::ReadAllBytes($fixture.file)) $fixture.mime $conversation $a.access_token
  if(-not $asset.derivatives.thumbnail -or -not $asset.derivatives.display){throw 'Derivatives not advertised'}
  try{$null=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url?variant=thumbnail") $null $b.access_token;throw 'Unbound peer access allowed'}
  catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
  $preview=Invoke-RealtimeApi POST ("/admin/moderation/media/"+$asset.id+"/preview-url?variant=display") $null $admin.access_token
  $null=Invoke-WebRequest ($javaBase+$preview.url) -TimeoutSec 15
  $null=Invoke-RealtimeApi POST ("/admin/moderation/media/"+$asset.id+"/approve") @{reason='Synthetic derivatives smoke'} $admin.access_token
  $sent=Invoke-RealtimeApi POST ($path+'/messages') @{type='image';media_kind=$fixture.kind;media_asset_id=$asset.id;client_message_id=('derivative-smoke-'+$fixture.kind);content='synthetic derivative'} $a.access_token
  $urls=@{}
  foreach($variant in @('thumbnail','display')){
   $grant=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url?variant="+$variant) $null $b.access_token;$urls[$variant]=$grant.url
   $downloadPath=Join-Path $javaProjectRoot ('.tools/derivative-download-'+$fixture.kind+'-'+$variant)
   Invoke-WebRequest ($javaBase+$grant.url) -OutFile $downloadPath -TimeoutSec 15
   $probe=& $media.ffprobe -v error -count_frames -show_entries stream=width,height,nb_read_frames -of json $downloadPath
   if($LASTEXITCODE -ne 0){throw 'Derivative probe failed'};$decoded=($probe -join "`n")|ConvertFrom-Json
   $expected=if($variant -eq 'thumbnail'){$fixture.width}else{$fixture.display}
   if($decoded.streams[0].width -ne $expected){throw 'Derivative dimensions wrong'}
   if($fixture.kind -eq 'gif' -and $variant -eq 'display' -and [int]$decoded.streams[0].nb_read_frames -lt 2){throw 'GIF display lost animation'}
  }
  $partial=Invoke-WebRequest ($javaBase+$urls.thumbnail) -Headers @{Range='bytes=0-7'} -TimeoutSec 15
  if($partial.StatusCode -ne 206 -or $partial.RawContentLength -ne 8){throw 'Derivative Range failed'}
  $own=Invoke-RealtimeApi POST ("/media/"+$asset.id+"/access-url?variant=display") $null $a.access_token
  $null=Invoke-RealtimeApi DELETE ($path+'/messages/'+$sent.id) $null $b.access_token
  foreach($url in $urls.Values){try{$null=Invoke-WebRequest ($javaBase+$url);throw 'Hidden derivative readable'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}}
  $null=Invoke-WebRequest ($javaBase+$own.url)
  $null=Invoke-RealtimeApi POST ($path+'/messages/'+$sent.id+'/recall') $null $a.access_token
  try{$null=Invoke-WebRequest ($javaBase+$own.url);throw 'Recalled derivative readable'}catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
  $thumbPath=Join-Path $javaProjectRoot ('.data/media/'+$asset.id+'.thumb.png')
  if(-not (Test-Path -LiteralPath $thumbPath)){throw 'Expected derivative file missing'}
  $null=Invoke-RealtimeApi DELETE ("/media/"+$asset.id) $null $a.access_token
  foreach($suffix in @('.png','.gif','.thumb.png','.display.png')){
   if(Test-Path -LiteralPath (Join-Path $javaProjectRoot ('.data/media/'+$asset.id+$suffix))){throw 'Deleted derivative file remains'}
  }
 }
 $report=@{checked_at=[DateTime]::UtcNow.ToString('o');target=$javaBase;schema='V15';result='passed'
  checks=@('png_thumbnail_and_display_sizes','gif_static_thumbnail_and_animated_display','pending_peer_denied','admin_variant_preview','signed_variant_download','range_206','personal_hide_revokes_derivatives','recall_revokes_derivatives','original_and_derivative_deletion')
  note='Synthetic local data only. Legacy backfill/retry and account erasure are separately integration-tested; no Flutter changes or real cloud/moderation integration.'}
 [IO.File]::WriteAllText((Join-Path $javaProjectRoot 'docs/local-derivatives-smoke.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
 $report|ConvertTo-Json -Depth 5
}finally{
 if($a){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$a.refresh_token}}
 if($b){$null=Invoke-RealtimeApi POST '/auth/logout' @{refresh_token=$b.refresh_token}}
 if($admin){$null=Invoke-RealtimeApi POST '/admin/auth/logout' $null $admin.access_token}
}
