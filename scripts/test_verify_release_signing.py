"""Exercise the signed-identity formats emitted by Android apksigner."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'verify_release_signing', Path(__file__).with_name('verify-release-signing.py')
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SignerDigestsTest(unittest.TestCase):
    expected = 'ab' * 32
    other = 'cd' * 32

    def test_numbered_signer(self):
        self.assertEqual(module.signer_digests(
            f'Signer #1 certificate SHA-256 digest: {self.expected}\n'
        ), {self.expected})

    def test_build_tools_37_scheme_signers(self):
        output = '\n'.join(
            f'{label}: certificate SHA-256 digest: {self.expected}'
            for label in ('V1 Signer', 'V2 Signer', 'V3 Signer', 'V3.1 Signer', 'V4 Signer')
        )
        self.assertEqual(module.signer_digests(output), {self.expected})
        output += f'\nV3 Signer: certificate SHA-256 digest: {self.other}'
        self.assertNotEqual(module.signer_digests(output), {self.expected})

    def test_same_identity_across_sdk_ranges(self):
        output = '\n'.join(
            f'Signer (minSdkVersion={lo}, maxSdkVersion={hi}) certificate SHA-256 digest: {self.expected}'
            for lo, hi in [(24, 32), (33, 2147483647)]
        )
        self.assertEqual(module.signer_digests(output), {self.expected})

    def test_unexpected_signer_still_rejected(self):
        output = (
            f'Signer #1 certificate SHA-256 digest: {self.expected}\n'
            f'Signer #2 certificate SHA-256 digest: {self.other}\n'
        )
        self.assertNotEqual(module.signer_digests(output), {self.expected})

    def test_source_stamp_is_not_an_app_signer(self):
        output = (
            f'Signer #1 certificate SHA-256 digest: {self.expected.upper()}\n'
            f'Source Stamp Signer certificate SHA-256 digest: {self.other}\n'
        )
        self.assertEqual(module.signer_digests(output), {self.expected})

    def test_missing_or_malformed_fingerprint_rejected(self):
        for output in ('', 'DOES NOT VERIFY', 'Signer #1 certificate SHA-256 digest: abcd'):
            self.assertNotEqual(module.signer_digests(output), {self.expected})


if __name__ == '__main__':
    unittest.main()
