#!/usr/bin/env bash
set -euo pipefail
project=$(cd "$(dirname "$0")" && pwd)
build="$project/.build"
sdk=${ANDROID_HOME:-"$HOME/Android/Sdk"}
ndk=${ANDROID_NDK_HOME:-"$build/android-ndk-r28c"}
build_tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
key_dir=${INVOKEVPN_ANDROID_SIGNING_DIR:-"$HOME/.local/share/invokevpn/android-signing"}
version_name=1.2.7
version_code=11
apk="$project/dist/InvokeVPN-$version_name.apk"
src="$project/app/src/main"

export ANDROID_HOME="$sdk" ANDROID_NDK_HOME="$ndk"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
mkdir -p "$build/tools" "$project/dist"
export PATH="$build/tools:$PATH"

if [[ "${1:-}" != "--reuse-core" ]]; then
    test -d "$ndk/toolchains/llvm/prebuilt"
    version=v0.0.0-20261117202452-47699113848a
    GOBIN="$build/tools" go install "golang.org/x/mobile/cmd/gomobile@$version" "golang.org/x/mobile/cmd/gobind@$version"
    (
      cd "$project/core"
      gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -trimpath -ldflags="-s -w" -o "$build/androidcore.aar" .
    )
fi
test -s "$build/androidcore.aar"
test -f "$platform"

staging=$(mktemp -d "$build/apk.XXXXXX")
trap 'python3 -c "import shutil,sys; shutil.rmtree(sys.argv[1])" "$staging"' EXIT
mkdir -p "$staging/generated" "$staging/classes" "$staging/dex"
sed 's|<manifest xmlns:|<manifest package="su.xokeza.invokevpn" xmlns:|' "$src/AndroidManifest.xml" > "$staging/AndroidManifest.xml"
mkdir -p "$staging/res"
cp -R "$src/res/." "$staging/res/"
vendor="$project/vendor"
(cd "$vendor" && sha256sum --check --quiet SHA256SUMS)
cp "$vendor/"*.jar "$staging/"
for archive in "$vendor/"*.aar; do
    module=$(basename "$archive" .aar)
    unzip -p "$archive" classes.jar > "$staging/$module.jar"
    if [[ "$module" != spring ]]; then
        unzip -oq "$archive" 'res/*' -d "$staging/$module"
        for values in "$staging/$module"/res/values*/*.xml; do
            mv "$values" "${values%/*}/${module//-/_}_${values##*/}"
        done
        cp -R "$staging/$module/res/." "$staging/res/"
    fi
done
python3 "$project/svg-to-vector.py" "$src/assets/icons" "$staging/res/drawable"
"$build_tools/aapt2" compile --dir "$staging/res" -o "$staging/compiled.zip"
"$build_tools/aapt2" link -I "$platform" --manifest "$staging/AndroidManifest.xml" --java "$staging/generated" \
    --min-sdk-version 26 --target-sdk-version 35 --version-code "$version_code" --version-name "$version_name" \
    --extra-packages com.example.liquidglass:androidx.core \
    -A "$src/assets" "$staging/compiled.zip" -o "$staging/resources.apk"
unzip -p "$build/androidcore.aar" classes.jar > "$staging/androidcore.jar"
# Compile the local LiquidGlassView against its pinned upstream helpers, keeping the original ABI.
javac -d "$staging/classes" "$staging/generated/com/example/liquidglass/R.java"
glass_cp="$platform:$staging/glass.jar:$staging/kotlin-stdlib.jar:$staging/classes"
kotlin_args=(-no-reflect -no-stdlib -language-version 2.2 -jvm-target 11 -module-name liquidglass_release
    "-Xfriend-paths=$staging/glass.jar" -classpath "$glass_cp" -d "$staging/classes"
    "$src/kotlin/com/example/liquidglass/LiquidGlassView.kt")
if command -v kotlinc >/dev/null; then
    kotlinc "${kotlin_args[@]}"
else
    java -cp "${GRADLE_HOME:?Set GRADLE_HOME or install Kotlin 2.2+}/lib/*" \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "${kotlin_args[@]}"
fi
zip -dq "$staging/glass.jar" 'com/example/liquidglass/LiquidGlassView.class' 'com/example/liquidglass/LiquidGlassView$*.class'
mapfile -d '' java_files < <(find "$src/java" "$staging/generated" -name '*.java' -print0)
javac -Xlint:-options -source 17 -target 17 -classpath "$staging/classes:$platform:$staging/*" -d "$staging/classes" "${java_files[@]}"
jar --create --file "$staging/app.jar" -C "$staging/classes" .
"$build_tools/d8" --release --min-api 26 --lib "$platform" --output "$staging/dex" "$staging/"*.jar
python3 - "$staging" "$build/androidcore.aar" "$vendor/glass.aar" <<'PY'
from pathlib import Path
import sys, zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'resources.apk','a') as apk:
    for dex in (p/'dex').glob('classes*.dex'):
        apk.write(dex,dex.name,compress_type=zipfile.ZIP_DEFLATED)
    for archive in sys.argv[2:]:
        with zipfile.ZipFile(archive) as aar:
            for name in aar.namelist():
                if name.startswith(('jni/arm64-v8a/', 'jni/x86_64/')) and name.endswith('.so'):
                    apk.writestr(name.replace('jni/','lib/',1),aar.read(name),compress_type=zipfile.ZIP_STORED)
PY
"$build_tools/zipalign" -f -P 16 4 "$staging/resources.apk" "$staging/aligned.apk"

mkdir -p -m 0700 "$key_dir"
if [[ ! -e "$key_dir/release.p12" ]]; then
    (
      umask 077
      test ! -e "$key_dir/password"
      python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$key_dir/password"
      keytool -genkeypair -storetype PKCS12 -keystore "$key_dir/release.p12" \
          -storepass:file "$key_dir/password" -alias invokevpn -keyalg RSA -keysize 3072 -validity 10000 \
          -dname "CN=InvokeVPN, O=InvokeVPN"
    )
fi
"$build_tools/apksigner" sign --ks "$key_dir/release.p12" --ks-key-alias invokevpn \
    --ks-pass "file:$key_dir/password" --v4-signing-enabled false --out "$apk" "$staging/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$apk"
"$build_tools/zipalign" -c -P 16 4 "$apk"
(cd "$project/dist" && sha256sum "$(basename "$apk")" > "$(basename "$apk").sha256")
echo "APK: $apk"
