from cryptography.fernet import Fernet


def generate_key() -> str:
    """Generate a URL-safe Fernet key suitable for DP_SECRET_KEY."""
    return Fernet.generate_key().decode("ascii")


class SecretBox:
    """Symmetric encryption for datasource passwords.

    The key must be the base64 string produced by ``generate_key`` (or
    ``Fernet.generate_key``), normally supplied through ``DP_SECRET_KEY``.
    """

    def __init__(self, key: str):
        if not key:
            raise ValueError("DP_SECRET_KEY is required to store datasource passwords")
        self._fernet = Fernet(key.encode("ascii"))

    def encrypt(self, plain: str) -> str:
        return self._fernet.encrypt(plain.encode("utf-8")).decode("ascii")

    def decrypt(self, token: str) -> str:
        return self._fernet.decrypt(token.encode("ascii")).decode("utf-8")
