# Builds WaveTV.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Requires: JDK 17 (Eclipse Adoptium) and Android SDK at C:\Android
# (build-tools + platforms installed via sdkmanager).
#
# Signing: the keystore password is NOT stored in this file. Set the
# WAVETV_KEYSTORE_PASS environment variable, or the script will prompt for it.

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $MyInvocation.MyCommand.Path
$app   = Join-Path $root 'app'
$build = Join-Path $root 'build'

$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory |
       Where-Object Name -like "jdk-17*" | Select-Object -First 1 -ExpandProperty FullName
if (-not $jdk) { throw "JDK 17 not found under C:\Program Files\Eclipse Adoptium" }
$sdk = 'C:\Android'
$bt  = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending |
       Select-Object -First 1 -ExpandProperty FullName
$platform = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name -Descending |
       Select-Object -First 1 -ExpandProperty FullName
$androidJar = "$platform\android.jar"

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$javac     = "$jdk\bin\javac.exe"
$jar       = "$jdk\bin\jar.exe"
$keytool   = "$jdk\bin\keytool.exe"
$aapt2     = "$bt\aapt2.exe"
$zipalign  = "$bt\zipalign.exe"
$apksigner = "$bt\apksigner.bat"
$d8        = "$bt\d8.bat"

# Resolve the signing password up front so the build doesn't fail at the last step.
$ksPass = $env:WAVETV_KEYSTORE_PASS
if (-not $ksPass) {
    $secure = Read-Host -Prompt "Keystore password (set WAVETV_KEYSTORE_PASS to skip)" -AsSecureString
    $ksPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
if (-not $ksPass) { throw "No keystore password supplied" }

Write-Host "JDK:        $jdk"
Write-Host "BuildTools: $bt"
Write-Host "Platform:   $platform"

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$build\gen", "$build\obj", "$build\dex" | Out-Null

Write-Host "`n[1/6] aapt2 compile resources"
& $aapt2 compile --dir "$app\res" -o "$build\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& $aapt2 link -o "$build\unsigned.apk" -I $androidJar `
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

Write-Host "[5/6] package + zipalign"
Push-Location "$build\dex"
& $jar uf "$build\unsigned.apk" classes.dex
Pop-Location
if ($LASTEXITCODE) { throw "jar update failed" }
& $zipalign -f 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

Write-Host "[6/6] sign"
# Keep this keystore. Whoever holds it can publish updates that install over
# this app; losing it means never being able to update an installed copy.
$ks = Join-Path $root 'wave-tv.jks'
if (-not (Test-Path $ks)) {
    Write-Host "      no keystore at $ks - generating one"
    & $keytool -genkeypair -keystore $ks -alias wavetv -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $ksPass -keypass $ksPass -dname "CN=Wave TV"
    if ($LASTEXITCODE) { throw "keytool failed" }
}
& $apksigner sign --ks $ks --ks-key-alias wavetv `
    --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --out "$root\WaveTV.apk" "$build\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
& $apksigner verify "$root\WaveTV.apk"

$sha = (Get-FileHash "$root\WaveTV.apk" -Algorithm SHA256).Hash
Write-Host "`nDone: $root\WaveTV.apk"
Write-Host "SHA256: $sha"
