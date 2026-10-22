#!/usr/bin/env python3
"""Reject unsigned APKs or a release signed with an unexpected certificate."""

import os
from pathlib import Path
import re
import subprocess
import sys


def signer_digests(output):
    # Build Tools 37 uses scheme-prefixed signers; older tools use numbered
    # signers or SDK-range signers.
    # Source stamp certificates are not the APK's signing identity.
    return {
        digest.lower()
        for digest in re.findall(
            r"^(?:Signer (?:#\d+|\([^\r\n]+\))|V[1-4](?:\.\d+)? Signer(?: #\d+)?:)"
            r" certificate SHA-256 digest: *([0-9a-fA-F]{64}) *$",
            output,
            re.MULTILINE,
        )
    }


def main():
    expected = os.environ.get("SIGNING_CERT_SHA256", "").strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        sys.exit("SIGNING_CERT_SHA256 must contain the fixed release certificate SHA-256.")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    versions = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file()]
    signer = max(versions, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name))) / "apksigner"
    if len(sys.argv) < 2:
        sys.exit("No APKs supplied.")
    for apk in sys.argv[1:]:
        result = subprocess.check_output([str(signer), "verify", "--print-certs", apk], text=True)
        digests = signer_digests(result)
        if digests != {expected}:
            actual = ", ".join(sorted(digests)) or "<no recognized signer fingerprint>"
            sys.exit(
                f"Signing identity mismatch: {apk}\n"
                f"apksigner: {signer}\nExpected certificate SHA-256: {expected}\n"
                f"Actual certificate SHA-256: {actual}\n"
                f"Certificate details:\n{result}"
            )
        print(f"Verified release signature: {Path(apk).name}")


if __name__ == "__main__":
    main()
