"""All unofficial SDK calls live here, behind a replaceable provider."""
from typing import Protocol
from garminconnect import Garmin, GarminConnectNotFoundError, GarminConnectConnectionError, GarminConnectAuthenticationError


class ActivityProvider(Protocol):
    def login(self, email: str, password: str, is_cn: bool) -> bool: ...
    def mfa(self, code: str) -> None: ...
    def restore(self, tokens: str, is_cn: bool) -> None: ...
    def tokens(self) -> str: ...
    def page(self, start: int, limit: int) -> list[dict]: ...
    def exists(self, activity_id: str) -> bool: ...


class PythonGarminProvider:
    def __init__(self):
        self.client = None
        self.client_state = None

    def login(self, email, password, is_cn):
        self.client = Garmin(email, password, is_cn=is_cn, return_on_mfa=True, retry_attempts=1)
        try:
            status, self.client_state = self.client.login()
            return status == "needs_mfa"
        finally:
            # MFA continuation uses the in-memory SDK session, not the password.
            self.client.password = None
            self.client.username = None

    def mfa(self, code):
        self.client.resume_login(self.client_state, code)
        self.client_state = None

    def restore(self, tokens, is_cn):
        self.client = Garmin(is_cn=is_cn, retry_attempts=1)
        # Inline JSON avoids the SDK's plaintext token-file persistence.
        self.client.login(tokens)

    def tokens(self):
        import json
        value = self.client.client.dumps()
        if not json.loads(value).get("di_token"):
            raise GarminConnectAuthenticationError("This login did not provide a persistable SDK session")
        return value

    def page(self, start, limit):
        value = self.client.get_activities(start, limit)
        if not isinstance(value, list):
            raise ValueError("incomplete page")
        return value

    def exists(self, activity_id):
        try:
            self.client.get_activity(activity_id)
            return True
        except GarminConnectNotFoundError:
            return False
        except GarminConnectConnectionError as error:
            if getattr(getattr(error, "response", None), "status_code", None) == 410:
                return False
            raise
        # Auth, rate limits, 5xx and transport errors propagate: never deletions.
