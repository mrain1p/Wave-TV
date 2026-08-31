# Builds WaveTV.aab - the Android App Bundle Google Play requires for new
# apps (Play will not accept a plain APK upload). Same no-Gradle toolchain as
# build.ps1: aapt2 (proto format) -> javac -> d8 -> bundletool -> jarsigner.
#
# The APK from build.ps1 remains the artifact for the Amazon Appstore and for
# sideloading; this bundle exists only for the Play upload. Both are built
# from the same sources and signed from the same keystore, which for Play
# becomes the *upload key* once Play App Signing is enrolled (see RELEASING.md).
#
# Requires: JDK 17, Android SDK at C:\Android, and tools\bundletool-all-*.jar
# (downloaded once from https://github.com/google/bundletool/releases).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build-aab.ps1            # signed
#   powershell -ExecutionPolicy Bypass -File build-aab.ps1 -Unsigned  # skip signing

param([switch]$Unsigned)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $MyInvocation.MyCommand.Path
$app   = Join-Path $root 'app'
$build = Join-Path $root 'build-aab'

$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory |
       Where-Object Name -like "jdk-17*" | Select-Object -First 1 -ExpandProperty FullName
if (-not $jdk) { throw "JDK 17 not found under C:\Program Files\Eclipse Adoptium" }
$sdk = 'C:\Android'
$bt  = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending |
       Select-Object -First 1 -ExpandProperty FullName
$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending |
       Select-Object -First 1 -ExpandProperty FullName
$androidJar = "$platform\android.jar"

$bundletool = Get-ChildItem "$root\tools" -Filter 'bundletool-all-*.jar' -ErrorAction SilentlyContinue |
       Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $bundletool) {
    throw "bundletool not found. Download bundletool-all-<version>.jar from " +
          "https://github.com/google/bundletool/releases into $root\tools\"
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$java      = "$jdk\bin\java.exe"
$javac     = "$jdk\bin\javac.exe"
$jarTool   = "$jdk\bin\jar.exe"
$jarsigner = "$jdk\bin\jarsigner.exe"
$aapt2     = "$bt\aapt2.exe"
$d8        = "$bt\d8.bat"

# Resolve the signing password up front, exactly as build.ps1 does, unless
# this is an unsigned build (Play rejects those; useful only for testing).
if (-not $Unsigned) {
    $ksPass = $env:WAVETV_KEYSTORE_PASS
    if (-not $ksPass) {
        $secure = Read-Host -Prompt "Keystore password (set WAVETV_KEYSTORE_PASS to skip)" -AsSecureString
        $ksPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }
    if (-not $ksPass) { throw "No keystore password supplied" }
    $env:WAVETV_KEYSTORE_PASS = $ksPass
}

Write-Host "JDK:        $jdk"
Write-Host "BuildTools: $bt"
Write-Host "Platform:   $platform"
Write-Host "Bundletool: $bundletool"

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$build\gen", "$build\obj", "$build\dex",
    "$build\staging\manifest", "$build\staging\dex" | Out-Null

Write-Host "`n[1/6] aapt2 compile resources"
& $aapt2 compile --dir "$app\res" -o "$build\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }

# --proto-format is the difference from build.ps1: a bundle carries its
# resources as protobuf, not the binary format an APK uses.
Write-Host "[2/6] aapt2 link (proto format)"
& $aapt2 link --proto-format -o "$build\proto.apk" -I $androidJar `
    --manifest "$app\AndroidManifest.xml" -R "$build\res.zip" `
    --java "$build\gen" -A "$app\assets" --auto-add-overlay
if ($LASTEXITCODE) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = @(Get-ChildItem "$app\java" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
$sources += @(Get-ChildItem "$build\gen" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
& $javac -source 8 -target 8 -nowarn -encoding UTF-8 -classpath $androidJar -d "$build\obj" @sources
if ($LASTEXITCODE) { throw "javac failed" }

Write-Host "[4/6] d8 dex"
$classes = @(Get-ChildItem "$build\obj" -Recurse -Filter *.class | Select-Object -ExpandProperty FullName)
& $d8 --release --lib $androidJar --min-api 22 --output "$build\dex" @classes
if ($LASTEXITCODE) { throw "d8 failed" }

# A bundle module is the linked output rearranged: the manifest under
# manifest/, dex under dex/, resources.pb/res/assets as they came out of aapt2.
Write-Host "[5/6] stage module + bundletool"
Copy-Item "$build\proto.apk" "$build\proto.zip"
Expand-Archive "$build\proto.zip" "$build\extracted" -Force
Move-Item "$build\extracted\AndroidManifest.xml" "$build\staging\manifest\AndroidManifest.xml"
Move-Item "$build\extracted\resources.pb" "$build\staging\resources.pb"
if (Test-Path "$build\extracted\res")    { Move-Item "$build\extracted\res"    "$build\staging\res" }
if (Test-Path "$build\extracted\assets") { Move-Item "$build\extracted\assets" "$build\staging\assets" }
Copy-Item "$build\dex\classes.dex" "$build\staging\dex\classes.dex"

# jar, not Compress-Archive: bundletool requires forward-slash zip entries,
# which Windows' Compress-Archive does not produce.
& $jarTool cMf "$build\base.zip" -C "$build\staging" .
if ($LASTEXITCODE) { throw "jar (module zip) failed" }

& $java -jar $bundletool build-bundle --modules="$build\base.zip" --output="$root\WaveTV.aab" --overwrite
if ($LASTEXITCODE) { throw "bundletool build-bundle failed" }
& $java -jar $bundletool validate --bundle="$root\WaveTV.aab" | Select-Object -First 12
if ($LASTEXITCODE) { throw "bundletool validate failed" }

if ($Unsigned) {
    Write-Host "`n[6/6] skipped signing (-Unsigned): Play will NOT accept this file"
} else {
    # Bundles are signed with jarsigner, not apksigner. Same keystore as the
    # APK; under Play App Signing this key becomes the upload key, and Google
    # re-signs what devices actually download.
    Write-Host "[6/6] jarsigner"
    $ks = Join-Path $root 'wave-tv.jks'
    if (-not (Test-Path $ks)) { throw "keystore not found at $ks - run build.ps1 once to create it" }
    & $jarsigner -keystore $ks -storepass:env WAVETV_KEYSTORE_PASS `
        -digestalg SHA-256 -sigalg SHA256withRSA "$root\WaveTV.aab" wavetv
    if ($LASTEXITCODE) { throw "jarsigner failed" }
}

$sha = (Get-FileHash "$root\WaveTV.aab" -Algorithm SHA256).Hash
Write-Host "`nDone: $root\WaveTV.aab"
Write-Host "SHA256: $sha"
