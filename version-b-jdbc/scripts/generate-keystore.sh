#!/usr/bin/env bash
# Generates the RSA keystore the Config Server uses to decrypt {cipher} values.
#
# Asymmetric (keystore) rather than a symmetric encrypt.key, deliberately: with an RSA keypair the
# private key never has to be distributed to anything but the Config Server, and operators can be
# given only the public half in order to ENCRYPT new values. A shared symmetric secret gives
# everyone who can encrypt the ability to decrypt.
#
# IMPORTANT: PKCS12 does NOT support a key password different from the store password. keytool
# silently ignores -keypass for a PKCS12 store, so encrypt.key-store.secret MUST equal
# encrypt.key-store.password or startup fails with:
#   UnrecoverableKeyException: Get Key failed: Given final block not properly padded
# (The alternative, JKS, does support separate passwords but is a deprecated proprietary format.)
#
# The keystore is NOT committed. In a deployed environment it is a mounted secret (see k8s/) and
# the password comes from the environment.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$HERE/../secrets"
KEYSTORE="$OUT_DIR/config-server.p12"

ALIAS="${ENCRYPT_KEYSTORE_ALIAS:-configkey}"
STORE_PASS="${ENCRYPT_KEYSTORE_PASSWORD:-keystore-secret}"

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/config-server.jks

if [ -f "$KEYSTORE" ]; then
  echo "Keystore already exists: $KEYSTORE"
  exit 0
fi

keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 3650 \
  -dname "CN=Config Server, OU=Platform, O=Example, L=Bengaluru, C=IN" \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STORE_PASS" \
  -keypass "$STORE_PASS"

chmod 600 "$KEYSTORE"
echo "Created $KEYSTORE (alias=$ALIAS, PKCS12, RSA 4096, 10y)"
