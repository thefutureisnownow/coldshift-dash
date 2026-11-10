#!/bin/sh
# Build and sign the APKs with the Android SDK command-line tools (no Gradle). One compile, two APKs:
#   build/dash-legacy.apk  targets Android 4.4 (API 19): older phones (such as a Huawei G7 or Y625)
#   build/dash-modern.apk  targets a current Android (API 35), from 6.0 up: newer phones (such as a Galaxy A22),
#                          which install it with a plain `adb install` and get asked for permissions in the app
# Both are compiled against the new SDK; code newer than 4.4 is behind version checks (Api.java). Only the
# manifest's SDK versions differ, which aapt2 sets here (AndroidManifest.xml has no <uses-sdk>).
#
# Settings, from the environment (all optional):
#   ANDROID_HOME      the Android SDK (default ~/Library/Android/sdk)
#   BUILD_TOOLS       its build-tools version (default 36.1.0)
#   ANDROID_PLATFORM  the platform compiled against (default android-35)
#   JAVA_HOME         a JDK 17 or newer (default: the one inside Android Studio on macOS)
#   KS                the keystore to sign with (default ~/.android/debug.keystore, made if missing)
#   KS_PASS           its password (default android); KEY_ALIAS, the key in it (default: its only key);
#   KEY_PASS          the key's password (default KS_PASS)
#   VERSION_NAME, VERSION_CODE   override the manifest's versionName and versionCode
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.1.0}
JAR=$SDK/platforms/${ANDROID_PLATFORM:-android-35}/android.jar
LIBS=$(ls libs/*.jar 2>/dev/null | tr '\n' ':')
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT=build
# the libraries, map themes and routing data are not committed (a fresh checkout or worktree lacks them)
if [ ! -d assets/mapsforge ] || [ -z "$LIBS" ]; then
    echo "Libraries or map themes missing: run libs/fetch.sh first"; exit 1
fi
rm -rf $OUT && mkdir -p $OUT/classes

if ! JOUT=$(javac -source 8 -target 8 -bootclasspath "$JAR" -cp "${LIBS}." -d $OUT/classes $(find src -name '*.java') 2>&1); then
    echo "$JOUT" | grep -v "^Note\|^warning" ; echo "COMPILE FAILED"; exit 1
fi
"$BT/d8" --min-api 19 --lib "$JAR" --output $OUT $(find $OUT/classes -name '*.class') $(ls libs/*.jar 2>/dev/null)

RESZIP=""
if [ -d res ]; then
    $BT/aapt2 compile --dir res -o $OUT/res.zip
    RESZIP=$OUT/res.zip
fi
KS=${KS:-$HOME/.android/debug.keystore}
export KS_PASS=${KS_PASS:-android}
export KEY_PASS=${KEY_PASS:-$KS_PASS}
[ -f "$KS" ] || mkdir -p "$(dirname "$KS")"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass:env KS_PASS -keypass:env KEY_PASS \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

# apk <name> <minSdk> <targetSdk>
apk() {
    $BT/aapt2 link -o $OUT/$1-base.apk -I "$JAR" --manifest AndroidManifest.xml \
        --min-sdk-version $2 --target-sdk-version $3 $( [ -d assets ] && echo "-A assets" ) $RESZIP \
        ${VERSION_NAME:+--version-name "$VERSION_NAME"} ${VERSION_CODE:+--version-code "$VERSION_CODE"} \
        $( [ -n "${VERSION_NAME:-}${VERSION_CODE:-}" ] && echo --replace-version )
    (cd $OUT && cp $1-base.apk $1-unsigned.apk && zip -q -j $1-unsigned.apk classes.dex)
    $BT/zipalign -f 4 $OUT/$1-unsigned.apk $OUT/$1-aligned.apk
    $BT/apksigner sign --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} \
        --min-sdk-version $2 --out $OUT/$1.apk $OUT/$1-aligned.apk
    rm -f $OUT/$1-base.apk $OUT/$1-unsigned.apk $OUT/$1-aligned.apk $OUT/$1.apk.idsig
}
apk dash-legacy 19 19
apk dash-modern 23 35
ls -l $OUT/dash-legacy.apk $OUT/dash-modern.apk
