#!/usr/bin/env python3
"""Reject unsigned APKs or a release signed with an unexpected certificate."""

import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    expected = os.environ.get("SIGNING_CERT_SHA256", "").replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        sys.exit("SIGNING_CERT_SHA256 must contain the fixed release certificate SHA-256.")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    versions = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file()]
    signer = max(versions, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name))) / "apksigner"
    if len(sys.argv) < 2:
        sys.exit("No APKs supplied.")
    for apk in sys.argv[1:]:
        result = subprocess.check_output([str(signer), "verify", "--print-certs", apk], text=True)
        digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result)
        if digests != [expected]:
            sys.exit(f"Signing identity mismatch: {apk}")
        print(f"Verified release signature: {Path(apk).name}")


if __name__ == "__main__":
    main()
