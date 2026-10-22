#!/usr/bin/env python3
"""Create a persistent signing key once, with private backup material kept outside Git."""
import base64
import hashlib
import os
from pathlib import Path
import secrets
import subprocess

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
directory = root / '.local' / 'release-signing'
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
if any(directory.iterdir()):
    raise SystemExit('Signing directory is not empty. Reuse and back up the existing key; never overwrite it.')
os.chmod(directory, 0o700)
password = secrets.token_hex(32)
for name in ('SIGNING_STORE_PASSWORD', 'SIGNING_KEY_PASSWORD'):
    with open(directory / name, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
        f.write(password + '\n')
env = dict(os.environ, RUFFLE_RELEASE_PASSWORD=password)
key = directory / 'android-release.jks'
subprocess.run([
    'keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'JKS',
    '-alias', 'ruffle-release', '-storepass:env', 'RUFFLE_RELEASE_PASSWORD',
    '-keypass:env', 'RUFFLE_RELEASE_PASSWORD', '-keyalg', 'RSA', '-keysize', '3072',
    '-validity', '36500', '-dname', 'CN=Ruffle Android Release',
], env=env, check=True)
certificate = subprocess.check_output([
    'keytool', '-exportcert', '-keystore', str(key), '-alias', 'ruffle-release',
    '-storepass:env', 'RUFFLE_RELEASE_PASSWORD',
], env=env)
fingerprint = hashlib.sha256(certificate).hexdigest()
values = {
    'KEYSTORE': base64.b64encode(key.read_bytes()).decode(),
    'SIGNING_STORE_PASSWORD': password,
    'SIGNING_KEY_ALIAS': 'ruffle-release',
    'SIGNING_KEY_PASSWORD': password,
    'SIGNING_CERT_SHA256': fingerprint,
}
for name, value in values.items():
    path = directory / name
    if path.exists():
        continue
    with open(path, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as f:
        f.write(value + '\n')
os.chmod(key, 0o600)
print('Persistent signing material saved to:', directory)
print('Certificate SHA-256:', fingerprint)
print('Back up the entire directory securely. It is excluded from Git.')
