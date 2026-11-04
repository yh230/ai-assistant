# tests/unit/test_crypto.py
import importlib.util
import unittest

HAS_CRYPTO = importlib.util.find_spec("cryptography") is not None


@unittest.skipUnless(HAS_CRYPTO, "cryptography not installed")
class SecretBoxTest(unittest.TestCase):
    def test_roundtrip(self):
        from app.core.crypto import SecretBox, generate_key

        box = SecretBox(generate_key())
        token = box.encrypt("p@ssw0rd")
        self.assertNotEqual(token, "p@ssw0rd")
        self.assertEqual(box.decrypt(token), "p@ssw0rd")

    def test_requires_configured_key(self):
        from app.core.crypto import SecretBox

        with self.assertRaises(ValueError) as ctx:
            SecretBox("")
        self.assertIn("DP_SECRET_KEY", str(ctx.exception))

    def test_token_from_another_key_is_rejected(self):
        from cryptography.fernet import InvalidToken

        from app.core.crypto import SecretBox, generate_key

        token = SecretBox(generate_key()).encrypt("secret")
        with self.assertRaises(InvalidToken):
            SecretBox(generate_key()).decrypt(token)
