#!/bin/sh
# Requires GitHub CLI authenticated with repository secrets write permission.
set -eu
cd "$(dirname "$0")/.."
repository=${1:-aojiaoxiaolinlin/ruffle-android}
command -v gh >/dev/null || { echo 'Install GitHub CLI and run gh auth login first.' >&2; exit 1; }
for name in KEYSTORE SIGNING_STORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD SIGNING_CERT_SHA256; do
    test -s ".local/release-signing/$name" || { echo "Missing signing material: $name" >&2; exit 1; }
done
for name in KEYSTORE SIGNING_STORE_PASSWORD SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD SIGNING_CERT_SHA256; do
    gh secret set "$name" --repo "$repository" < ".local/release-signing/$name"
done
echo 'Persistent signing secrets configured. Do not regenerate the key.'
