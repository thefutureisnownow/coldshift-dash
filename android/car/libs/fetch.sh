#!/bin/sh
# Downloads the third-party libraries and map theme resources the car app needs.
# They are not committed: Mapsforge is LGPL-3.0, androidsvg and ZXing are Apache-2.0, and the
# theme symbols carry their own licences (see the mapsforge-themes jar).
#
#   Mapsforge 0.23.0 runs on Android 4.4 although its build files declare API 21 (tested on
#   the G7: map, dark Motorider theme, zoom and pan all work). 0.22.0 is not on Maven Central.
set -eu
cd "$(dirname "$0")"
V=0.23.0
B=https://repo1.maven.org/maven2/org/mapsforge
for m in mapsforge-core mapsforge-map mapsforge-map-reader mapsforge-map-android mapsforge-themes; do
  [ -f "$m-$V.jar" ] || curl -fsSL -o "$m-$V.jar" "$B/$m/$V/$m-$V.jar"
done
[ -f androidsvg-1.4.jar ] || curl -fsSL -o androidsvg-1.4.jar \
  https://repo1.maven.org/maven2/com/caverock/androidsvg/1.4/androidsvg-1.4.jar
# ZXing core (Apache-2.0) reads QR codes from the camera (joining a convoy). 3.3.3 is the last built for
# Java 7, which d8 takes for API 19 without desugaring.
[ -f core-3.3.3.jar ] || curl -fsSL -o core-3.3.3.jar https://repo1.maven.org/maven2/com/google/zxing/core/3.3.3/core-3.3.3.jar
SHA1="shasum -a 1"; command -v shasum >/dev/null || SHA1=sha1sum
echo "b640badcc97f18867c4dfd249ef8d20ec0204c07  core-3.3.3.jar" | $SHA1 -c - >/dev/null
# The render themes and their symbols are jar resources; d8 drops those, so they go in as assets
# (Android's class loader reads them straight from the APK).
mkdir -p ../assets
unzip -qo "mapsforge-themes-$V.jar" 'assets/*' -d /tmp/mapsforge-themes.$$
cp -R /tmp/mapsforge-themes.$$/assets/* ../assets/
rm -rf /tmp/mapsforge-themes.$$

# BRouter (offline routing, MIT; github.com/abrensch/brouter) is not on Maven Central, so its
# routing core is built from the release source. It needs Java 9+ syntax, hence --release 11; d8
# turns that into dex for API 19 (tested on a Huawei G7 on Android 4.4).
BR=1.7.10
if [ ! -f "brouter-$BR.jar" ] || [ ! -f ../assets/brouter/car-vario.brf ]; then
    JDK="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin"
    T=/tmp/brouter-src.$$
    mkdir -p $T/classes
    curl -fsSL "https://github.com/abrensch/brouter/archive/refs/tags/v$BR.tar.gz" | tar -xz -C $T
    S=$T/brouter-$BR
    find $S/brouter-util/src/main $S/brouter-codec/src/main $S/brouter-expressions/src/main \
        $S/brouter-mapaccess/src/main $S/brouter-core/src/main -name '*.java' > $T/sources.txt
    "$JDK/javac" -nowarn -encoding UTF-8 --release 11 -d $T/classes @$T/sources.txt
    "$JDK/jar" cf "brouter-$BR.jar" -C $T/classes .
    mkdir -p ../assets/brouter
    cp $S/misc/profiles2/car-vario.brf $S/misc/profiles2/lookups.dat ../assets/brouter/
    cp $S/LICENSE ../assets/brouter/LICENSE
    rm -rf $T
fi
echo "libs and map theme assets ready"
