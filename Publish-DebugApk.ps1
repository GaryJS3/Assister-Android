[CmdletBinding()]
param([uri]$HostUrl = 'http://10.44.0.33:8081')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$root = $PSScriptRoot
$credentialPath = Join-Path $root 'assister-dev-token.txt'
if (!(Test-Path -LiteralPath $credentialPath)) { throw 'Missing assister-dev-token.txt upload credential.' }
$uploadCredential = (Get-Content -LiteralPath $credentialPath -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($uploadCredential)) { throw 'Upload credential is empty.' }
if (!$HostUrl.IsAbsoluteUri -or $HostUrl.Scheme -notin @('http', 'https') -or $HostUrl.AbsolutePath -ne '/' -or $HostUrl.Query -or $HostUrl.UserInfo) { throw 'HostUrl must be an HTTP(S) origin.' }
if (!$env:JAVA_HOME -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr')) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "APK build failed (exit $LASTEXITCODE); upload stopped." }
    $apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
    # Read the packaged manifest, rather than trusting Gradle source or stale output metadata.
    $sdkLine = Get-Content (Join-Path $root 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $sdk = ($sdkLine -replace '^sdk.dir=', '').Replace('\:', ':').Replace('\\', '\')
    $aapt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'aapt.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (!$aapt) { throw 'Cannot find Android SDK aapt.exe to read the APK version.' }
    $badging = & $aapt dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK manifest.' }
    $packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if ($packageLine -notmatch "^package: name='net.thegary.assister' versionCode='([0-9]+)' versionName='([^']+)'") { throw 'Unexpected APK application identity or manifest.' }
    $build = $Matches[1]
    $version = $Matches[2]
    if ($version -notmatch '^\d+\.\d+(\.\d+){0,2}$') { throw 'APK versionName must be numeric major.minor[.build[.revision]].' }
    $size = (Get-Item -LiteralPath $apk).Length
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($size -gt 512MB) { throw 'APK exceeds the 512 MiB upload limit.' }
    Write-Host "Publishing Assister $version (build $build), $size bytes"
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $stream = [System.IO.File]::OpenRead($apk)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Put, [uri]::new($HostUrl, "api/updates/assister/android/$version`?name=Assister.apk"))
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $uploadCredential)
    $request.Content = [System.Net.Http.StreamContent]::new($stream)
    $request.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
    $response = $null
    $download = $null
    $verificationPath = Join-Path ([System.IO.Path]::GetTempPath()) ("assister-verify-" + [guid]::NewGuid() + '.apk')
    try {
        try { $response = $client.SendAsync($request).GetAwaiter().GetResult() }
        catch { throw 'Upload connection failed; check host availability. Credential was not logged.' }
        switch ([int]$response.StatusCode) {
            201 { }
            401 { throw 'Upload token rejected (401).' }
            409 { Write-Host "Version $version already exists (409). No version changes made."; return }
            413 { throw 'Host rejected the package size (413; 512 MiB limit).' }
            default { throw "Upload failed (HTTP $([int]$response.StatusCode))." }
        }
        $release = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ($release.size -ne $size -or $release.sha256 -ne $hash) { throw 'Published metadata size/SHA-256 does not match the local APK.' }
        if ($release.appId -ne 'assister' -or $release.platform -ne 'android' -or [version]$release.version -ne [version]($version + ('.0' * (4 - $version.Split('.').Count)))) { throw 'Published release identity/version does not match the APK.' }
        $downloadUri = [uri]::new($HostUrl, [string]$release.downloadUrl)
        if ($downloadUri.Scheme -ne $HostUrl.Scheme -or $downloadUri.Authority -ne $HostUrl.Authority -or $downloadUri.UserInfo) { throw 'Returned download URL is not on the same host.' }
        # Authorization is set only on the upload request, never on this public GET.
        $download = $client.GetAsync($downloadUri, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if ([int]$download.StatusCode -ne 200) { throw "Public download failed (HTTP $([int]$download.StatusCode))." }
        $output = [System.IO.File]::Create($verificationPath)
        try { [void]$download.Content.CopyToAsync($output).GetAwaiter().GetResult() } finally { $output.Dispose() }
        if ((Get-Item -LiteralPath $verificationPath).Length -ne $size -or (Get-FileHash -LiteralPath $verificationPath -Algorithm SHA256).Hash -ne $hash) { throw 'Public download size/SHA-256 verification failed.' }
        Write-Host "Published $($release.version). Metadata and unauthenticated download verified."
        $release | ConvertTo-Json -Depth 5
    } finally {
        if ($download) { $download.Dispose() }
        if ($response) { $response.Dispose() }
        $request.Dispose()
        $stream.Dispose()
        $client.Dispose()
        $handler.Dispose()
        $uploadCredential = $null
        if (Test-Path -LiteralPath $verificationPath) { Remove-Item -LiteralPath $verificationPath }
    }
} finally { Pop-Location }
