#!/bin/sh
# A release of Coldshift Dash, built and signed on this computer (GitHub only stores the files):
#
#   tools/release.sh 0.2.0            both APKs, signed with the release key, and SHA256SUMS in build/release/0.2.0/
#   tools/release.sh 0.2.0 --upload   ... then the GitHub Release v0.2.0 with them (gh; tags the current commit)
#
# 0.2.0 becomes versionName 0.2.0 and versionCode 200 (major*10000 + minor*100 + patch: each below 100), and the
# files are dash-legacy.apk (Android 4.4 and up) and dash-modern.apk (6 and up), which the website links to.
# The release key: ~/.android/release-keystores/coldshift-dash-release.env (KEY_ENV=… for another), its password
# in the login keychain. Without it there is no release:
# a debug-signed APK could never be updated by a release-signed one.
set -eu
cd "$(dirname "$0")/.."
VERSION=${1:-}
UPLOAD=${2:-}
case "$VERSION" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "usage: tools/release.sh <major.minor.patch> [--upload]"; exit 1;;
esac
IFS=. read -r MAJOR MINOR PATCH <<V
$VERSION
V
[ "$MINOR" -lt 100 ] && [ "$PATCH" -lt 100 ] || { echo "minor and patch must stay below 100"; exit 1; }
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

if [ -n "$(git status --porcelain)" ]; then
  [ "$UPLOAD" = "--upload" ] && { echo "the working tree has changes: commit them first, so the release is that commit"; exit 1; }
  echo "(the working tree has changes: fine for a try, not for a release)"
fi
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
KEY_ENV=${KEY_ENV:-$HOME/.android/release-keystores/coldshift-dash-release.env}
[ -f "$KEY_ENV" ] || { echo "no $KEY_ENV (the release key's settings)"; exit 1; }
set -a; . "$KEY_ENV"; set +a
[ -f "$KS" ] || { echo "no $KS (the release key)"; exit 1; }
[ -n "$KS_PASS" ] || { echo "the release key's password isn't in the keychain"; exit 1; }

echo "== Coldshift Dash $VERSION ($CODE), $(git rev-parse --short HEAD)"
VERSION_NAME=$VERSION VERSION_CODE=$CODE android/car/build.sh
OUT=build/release/$VERSION
rm -rf "$OUT" && mkdir -p "$OUT"
cp android/car/build/dash-legacy.apk android/car/build/dash-modern.apk "$OUT/"

# signed with the release key, and nothing else
BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)
WANT=$(keytool -list -v -keystore "$KS" -storepass:env KS_PASS -alias "$KEY_ALIAS" 2>/dev/null | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
for apk in "$OUT"/*.apk; do
  GOT=$("$BT/apksigner" verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)
  [ "$GOT" = "$WANT" ] || { echo "$apk is not signed with the release key"; exit 1; }
done
(cd "$OUT" && shasum -a 256 dash-legacy.apk dash-modern.apk > SHA256SUMS)
echo "signed with the release key (SHA-256 $(echo "$WANT" | cut -c1-16)…):"
cat "$OUT/SHA256SUMS"

if [ "$UPLOAD" = "--upload" ]; then
  git tag -a "v$VERSION" -m "Coldshift Dash $VERSION"
  git push origin "v$VERSION"
  gh release create "v$VERSION" "$OUT/dash-legacy.apk" "$OUT/dash-modern.apk" "$OUT/SHA256SUMS" \
    --title "Coldshift Dash $VERSION" --generate-notes
fi
