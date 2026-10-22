#!/bin/sh
# Build a production-package APK using the same persistent key as CI.
set -eu
cd "$(dirname "$0")/.."
if [ "$#" -ne 2 ]; then
    echo "Usage: sh scripts/build-local-release.sh VERSION_CODE VERSION_NAME" >&2
    echo 'Choose a version code higher than every published build; never reset the CI baseline.' >&2
    exit 1
fi
signing_dir=${RUFFLE_SIGNING_DIR:-"$PWD/.local/release-signing"}
SIGNING_STORE_PASSWORD=$(cat "$signing_dir/SIGNING_STORE_PASSWORD")
SIGNING_KEY_PASSWORD=$(cat "$signing_dir/SIGNING_KEY_PASSWORD")
SIGNING_KEY_ALIAS=$(cat "$signing_dir/SIGNING_KEY_ALIAS")
SIGNING_CERT_SHA256=$(cat "$signing_dir/SIGNING_CERT_SHA256")
export SIGNING_STORE_PASSWORD SIGNING_KEY_PASSWORD SIGNING_KEY_ALIAS SIGNING_CERT_SHA256
./gradlew :app:assembleRelease \
    -PreleaseKeystorePath="$signing_dir/android-release.jks" \
    -PreleaseVersionCode="$1" -PreleaseVersionName="$2" --console=plain
python3 scripts/verify-release-signing.py app/build/outputs/apk/release/*.apk
