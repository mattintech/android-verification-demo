#!/usr/bin/env bash
# Builds the two demo APKs with the Android SDK command-line tools (no Gradle):
#   out/acme-fieldagent.apk  - do-nothing install target ("not registered" in the demo)
#   out/acme-timesheets.apk  - do-nothing install target ("registered" in the demo)
#   out/acme-store.apk       - "Acme Apps", a stand-in company app store that installs both
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)}"
JAR="${ANDROID_JAR:-$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar}"
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KEYSTORE_PASS:-android}"

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000 >/dev/null

rm -rf build out && mkdir -p build out

sign() { "$BT/zipalign" -f 4 "$1" "$1.aligned" && "$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$2" "$1.aligned" && rm -f "$2.idsig"; }

build_target() {  # $1 = folder, $2 = output name
  echo "Building $2"
  "$BT/aapt2" compile --dir "$1/res" -o "build/$1-res.zip"
  "$BT/aapt2" link -o "build/$1.apk" -I "$JAR" --manifest "$1/AndroidManifest.xml" "build/$1-res.zip"
  sign "build/$1.apk" "out/$2"
}
build_target fieldagent acme-fieldagent.apk
build_target timesheets acme-timesheets.apk

echo "Building acme-store.apk"
mkdir -p build/store-assets build/classes build/dex build/gen
cp out/acme-fieldagent.apk build/store-assets/fieldagent.apk
cp out/acme-timesheets.apk build/store-assets/timesheets.apk
"$BT/aapt2" compile --dir store/res -o build/st-res.zip
"$BT/aapt2" link -o build/st.apk -I "$JAR" --manifest store/AndroidManifest.xml -A build/store-assets --java build/gen build/st-res.zip
javac -source 11 -target 11 -nowarn -Xlint:-options -Xlint:-deprecation -classpath "$JAR" -d build/classes $(find store/src build/gen -name '*.java')
"$BT/d8" --lib "$JAR" --min-api 28 --release --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../st.apk classes.dex)
sign build/st.apk out/acme-store.apk

for f in out/*.apk; do "$BT/apksigner" verify "$f"; done
ls -la out
