param([string]$SdkPath = 'D:\AndroidDevTools\android-sdk')
$ErrorActionPreference = 'Stop'
$probeRoot = $PSScriptRoot
$probeProject = [IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$probeBuild = Join-Path $probeRoot 'build\release'
$probeTools = Join-Path $SdkPath 'build-tools\36.0.0'
$probeAndroid = Join-Path $SdkPath 'platforms\android-36\android.jar'
$probeSigningPath = Join-Path $probeProject '.local\signing\release.private.json'
if (!(Test-Path -LiteralPath $probeSigningPath)) {
    throw 'Release signing configuration missing: see CONTRIBUTING.md. No test key will be generated.'
}
$probeSigning = Get-Content -LiteralPath $probeSigningPath -Raw | ConvertFrom-Json
$probeKey = [IO.Path]::GetFullPath((Join-Path $probeProject $probeSigning.keyStore))
if (!(Test-Path -LiteralPath $probeKey) -or !$probeSigning.alias -or !$probeSigning.storePassword -or !$probeSigning.keyPassword) {
    throw 'Incomplete release signing configuration'
}
function Invoke-ProbeTool([string]$ToolPath, [string[]]$ToolArguments) {
    & $ToolPath @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "$ToolPath exited with $LASTEXITCODE" }
}
Invoke-ProbeTool 'python' @((Join-Path $probeProject 'tools\project.py'),'check','--with-local-inputs')
$probeClasses = Join-Path $probeBuild 'classes'
$probeDex = Join-Path $probeBuild 'dex'
New-Item -ItemType Directory -Force -Path $probeClasses,$probeDex | Out-Null
$probePayload = Join-Path $probeBuild ('inputs-' + [Guid]::NewGuid().ToString('N'))
Invoke-ProbeTool 'python' @((Join-Path $probeProject 'tools\standalone_payload.py'),'--output',$probePayload)
$probeCompiler = Join-Path $SdkPath 'ndk\28.0.13004108\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android32-clang.cmd'
$probeDiagnostic = Join-Path $probeProject 'third_party\sources\dfroot'
Invoke-ProbeTool $probeCompiler @('-O2','-Wall','-Wextra','-fPIE','-pie','-I',$probeDiagnostic,
    (Join-Path $probeRoot 'native\standalone-bootstrap.c'),(Join-Path $probePayload 'standalone-diagnostic.c'),
    (Join-Path $probeDiagnostic 'diagnostic_flow.c'),'-Wl,-z,max-page-size=16384','-o',(Join-Path $probePayload 'native\liblpbootstrap.so'))
$probeManifest = Join-Path $probeRoot 'AndroidManifest.xml'
$probeSources = @(Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
$probeApiLibrary = Join-Path $probeRoot 'lib\hiddenapibypass-6.2.jar'
$probeClassPath = $probeAndroid + [IO.Path]::PathSeparator + $probeApiLibrary
Invoke-ProbeTool 'javac.exe' (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$probeClassPath,'-d',$probeClasses) + $probeSources)
$probeJar = Join-Path $probeBuild 'classes.jar'
Invoke-ProbeTool 'jar.exe' @('cf',$probeJar,'-C',$probeClasses,'.')
Invoke-ProbeTool (Join-Path $probeTools 'd8.bat') @('--lib',$probeAndroid,'--min-api','32','--output',$probeDex,$probeJar,$probeApiLibrary)
$probeUnsigned = Join-Path $probeBuild 'unsigned.apk'
$probeResources = Join-Path $probePayload 'compiled-res'
New-Item -ItemType Directory -Path $probeResources | Out-Null
# Windows aapt2 crashes on zip output for this resource set; flat directory output works.
Invoke-ProbeTool (Join-Path $probeTools 'aapt2.exe') @('compile','--dir',(Join-Path $probeRoot 'res'),'-o',$probeResources)
$probeResourceFiles = @(Get-ChildItem -LiteralPath $probeResources -Filter '*.flat' -File | Sort-Object Name | Select-Object -ExpandProperty FullName)
if (!$probeResourceFiles.Count) { throw 'No compiled Android resources' }
Invoke-ProbeTool (Join-Path $probeTools 'aapt2.exe') (@('link','-I',$probeAndroid,'--manifest',$probeManifest,'-o',$probeUnsigned) + $probeResourceFiles)
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$probeZip = [System.IO.Compression.ZipFile]::Open($probeUnsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    # Windows aapt2 -A emits backslashes inside nested asset entry names.
    # Android looks up literal forward-slash ZIP paths; add these explicitly.
    $probeAssetRoot=Join-Path $probePayload 'assets'
    $probeAssetPrefix=[IO.Path]::GetFullPath($probeAssetRoot).TrimEnd('\','/') + '\'
    foreach($probeAsset in @(Get-ChildItem -LiteralPath $probeAssetRoot -File -Recurse)) {
        $probeAssetPath=[IO.Path]::GetFullPath($probeAsset.FullName)
        if (!$probeAssetPath.StartsWith($probeAssetPrefix,[StringComparison]::OrdinalIgnoreCase)) { throw "Asset outside root: $probeAssetPath" }
        $probeAssetName='assets/'+$probeAssetPath.Substring($probeAssetPrefix.Length).Replace('\','/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,$probeAsset.FullName,$probeAssetName) | Out-Null
    }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,(Join-Path $probeDex 'classes.dex'),'classes.dex') | Out-Null
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,(Join-Path $probeRoot 'lib\hiddenapibypass-LICENSE.txt'),'assets/licenses/hiddenapibypass-Apache-2.0.txt') | Out-Null
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,(Join-Path $probeRoot 'LICENSE'),'assets/licenses/LinkPods-GPL-3.0.txt') | Out-Null
    if (Test-Path -LiteralPath (Join-Path $probeRoot 'UI_ASSET_NOTICES.md')) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,(Join-Path $probeRoot 'UI_ASSET_NOTICES.md'),'assets/licenses/LibrePods-UI-sources.txt') | Out-Null
    }
    foreach ($probeNative in @(Get-ChildItem -LiteralPath (Join-Path $probePayload 'native') -File)) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,$probeNative.FullName,('lib/arm64-v8a/' + $probeNative.Name)) | Out-Null
    }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($probeZip,(Join-Path $probePayload 'provenance.json'),'assets/root/provenance.json') | Out-Null
} finally { $probeZip.Dispose() }
$probeAligned = Join-Path $probeBuild 'aligned.apk'
Invoke-ProbeTool (Join-Path $probeTools 'zipalign.exe') @('-f','-p','4',$probeUnsigned,$probeAligned)
$probeApk = Join-Path $probeBuild 'LinkPods-v1.0.apk'
$probeOldStorePass=$env:LINKPODS_STORE_PASSWORD
$probeOldKeyPass=$env:LINKPODS_KEY_PASSWORD
try {
    $env:LINKPODS_STORE_PASSWORD=$probeSigning.storePassword
    $env:LINKPODS_KEY_PASSWORD=$probeSigning.keyPassword
    Invoke-ProbeTool (Join-Path $probeTools 'apksigner.bat') @('sign','--ks',$probeKey,'--ks-key-alias',$probeSigning.alias,'--ks-pass','env:LINKPODS_STORE_PASSWORD','--key-pass','env:LINKPODS_KEY_PASSWORD','--out',$probeApk,$probeAligned)
} finally {
    $env:LINKPODS_STORE_PASSWORD=$probeOldStorePass
    $env:LINKPODS_KEY_PASSWORD=$probeOldKeyPass
}
Invoke-ProbeTool (Join-Path $probeTools 'apksigner.bat') @('verify','--verbose',$probeApk)
Invoke-ProbeTool 'python' @((Join-Path $probeProject 'tools\standalone_payload.py'),'--verify-apk',$probeApk)
$probeBadging = & (Join-Path $probeTools 'aapt.exe') dump badging $probeApk
if ($LASTEXITCODE -ne 0 -or !($probeBadging -match "package: name='lp.pods' versionCode='101' versionName='1.0'") -or ($probeBadging -match 'application-debuggable')) {
    throw 'Release APK metadata validation failed'
}
$probeBadging
Get-FileHash -LiteralPath $probeApk -Algorithm SHA256
$probeStaging = Get-Item -LiteralPath $probePayload
if ($probeStaging.Parent.FullName -ne [IO.Path]::GetFullPath($probeBuild) -or
    $probeStaging.Name -notmatch '^inputs-[0-9a-f]{32}$' -or
    ($probeStaging.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Unexpected payload staging path; refusing cleanup'
}
Remove-Item -LiteralPath $probeStaging.FullName -Recurse -Force
