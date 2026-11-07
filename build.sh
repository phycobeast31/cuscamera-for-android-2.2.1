#!/bin/bash
# Builds customcam.jar, then converts it to a signed APK for Android 2.2.
# Requirements: JDK 6/7/8 (newer JDKs can't target Java 1.6 bytecode),
# Android SDK with platform android-8 (or any newer platform jar) + build-tools (dx, aapt, zipalign).
#
# Usage: ANDROID_HOME=/path/to/sdk ./build.sh

set -e
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-8/android.jar}"
BT="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/19.1.0}"   # old build-tools still support dx cleanly

rm -rf out && mkdir -p out/classes

# 1. Java source -> .class
javac -source 1.6 -target 1.6 -bootclasspath "$ANDROID_JAR" \
      -d out/classes src/com/example/customcam/*.java

# 2. .class -> customcam.jar   (the jar you asked for)
jar cf out/customcam.jar -C out/classes .

# 3. .jar -> classes.dex   (what Dalvik actually runs)
"$BT/dx" --dex --output=out/classes.dex out/customcam.jar

# 4. Package manifest + dex into an APK
"$BT/aapt" package -f -M AndroidManifest.xml -I "$ANDROID_JAR" -F out/customcam-unsigned.apk
( cd out && "$BT/aapt" add customcam-unsigned.apk classes.dex )

# 5. Sign (MD5withRSA/SHA1 digest is the safe choice for Froyo)
[ -f debug.keystore ] || keytool -genkey -v -keystore debug.keystore -alias customcam \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=CustomCam"
jarsigner -keystore debug.keystore -storepass android -keypass android \
    -sigalg SHA1withRSA -digestalg SHA1 -signedjar out/customcam-signed.apk out/customcam-unsigned.apk customcam

# 6. Align
"$BT/zipalign" -f 4 out/customcam-signed.apk out/customcam.apk

echo "Done: out/customcam.apk"
echo "Install: adb install -r out/customcam.apk"
