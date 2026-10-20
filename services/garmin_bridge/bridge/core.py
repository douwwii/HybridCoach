from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from cryptography.fernet import Fernet
from .provider import PythonGarminProvider


class BridgeError(Exception):
    def __init__(self, code, status=502):
        self.code, self.status = code, status


class Vault:
    """One encrypted document per key; Garmin tokens and activity snapshots never plaintext at rest."""
    def __init__(self, filename, master_key):
        self.cipher = Fernet(master_key.encode())
        self.filename = str(filename)
        Path(filename).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS vault (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
        os.chmod(filename, 0o600)

    def db(self):
        return sqlite3.connect(self.filename)

    def get(self, key, default=None):
        with self.db() as db:
            value = db.execute("SELECT value FROM vault WHERE key=?", (key,)).fetchone()
        return json.loads(self.cipher.decrypt(value[0])) if value else default

    def put(self, key, value):
        encrypted = self.cipher.encrypt(json.dumps(value, ensure_ascii=False).encode())
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO vault VALUES (?,?)", (key, encrypted))

    def delete(self, key):
        with self.db() as db:
            db.execute("DELETE FROM vault WHERE key=?", (key,))


def normalize(raw):
    def number(key, default=None):
        value = raw.get(key, default)
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise BridgeError("INCOMPLETE")
        return float(value)
    activity_id = raw.get("activityId")
    if isinstance(activity_id, bool) or not str(activity_id).isdigit():
        raise BridgeError("INCOMPLETE")
    sport_key = raw.get("activityType", {}).get("typeKey", "other")
    if not isinstance(sport_key, str):
        raise BridgeError("INCOMPLETE")
    if sport_key in {"running", "trail_running", "treadmill_running", "track_running", "virtual_run", "ultra_run", "indoor_track"}:
        sport = "RUN"
    elif sport_key in {"strength_training", "strength", "functional_strength_training"}:
        sport = "STRENGTH"
    else:
        sport = "OTHER"
    try:
        # Garmin's GMT/local strings are timezone-naive, with distinct meanings.
        utc = datetime.fromisoformat(raw["startTimeGMT"].replace("Z", "+00:00"))
        if utc.tzinfo is None:
            utc = utc.replace(tzinfo=timezone.utc)
        local = datetime.fromisoformat(raw["startTimeLocal"])
        local = local.replace(tzinfo=None)
    except (KeyError, ValueError, TypeError):
        raise BridgeError("INCOMPLETE") from None
    distance = number("distance")
    duration = number("duration")
    if duration is None:
        raise BridgeError("INCOMPLETE")
    hr = number("averageHR")
    if hr is not None and hr > 300:
        raise BridgeError("INCOMPLETE")
    name = raw.get("activityName") or sport_key
    if not isinstance(name, str) or len(name) > 1000:
        raise BridgeError("INCOMPLETE")
    value = dict(id=str(activity_id), name=name, sport=sport, sportKey=sport_key,
        startedAt=utc.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        localDateTime=local.isoformat(), distanceMeters=distance, durationSeconds=duration,
        movingSeconds=number("movingDuration"), elapsedSeconds=number("elapsedDuration"),
        averagePace=duration * 1000 / distance if distance else None,
        averageHr=hr, elevationGain=number("elevationGain"))
    value["fingerprint"] = hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return value


class Bridge:
    def __init__(self, vault, identity_key, factory=PythonGarminProvider):
        self.vault, self.identity_key, self.factory = vault, identity_key.encode(), factory
        self.lock = threading.Lock()
        self.pending = None
        self.failures = []

    @contextmanager
    def operation(self):
        if not self.lock.acquire(blocking=False):
            raise BridgeError("BUSY", 409)
        try:
            yield
        finally:
            self.lock.release()

    def status(self):
        session = self.vault.get("session")
        return dict(connected=session is not None, accountId=session["account"] if session else None)

    def _finish_login(self, provider, account, is_cn):
        self.vault.put("session", dict(account=account, isCn=is_cn, tokens=provider.tokens()))
        self.pending = None
        return dict(connected=True, accountId=account)

    def login(self, email, password, is_cn=False):
        with self.operation():
            self.failures = [t for t in self.failures if time.monotonic()-t < 300]
            if len(self.failures) >= 5:
                raise BridgeError("RATE_LIMIT", 429)
            self.failures.append(time.monotonic())
            self.pending = None
            # Constant across reconnects; does not reveal an email in Android/Room.
            account = hmac.new(self.identity_key, email.strip().lower().encode(), hashlib.sha256).hexdigest()
            provider = self.factory()
            if provider.login(email, password, is_cn):
                challenge = secrets.token_urlsafe(32)
                self.pending = dict(provider=provider, account=account, isCn=is_cn,
                    challenge=challenge, expires=time.monotonic()+300, attempts=0)
                return dict(connected=False, needsMfa=True, challengeId=challenge)
            return self._finish_login(provider, account, is_cn)

    def mfa(self, challenge, code):
        with self.operation():
            pending = self.pending
            if not pending or time.monotonic() > pending["expires"] or pending["attempts"] >= 3 or not secrets.compare_digest(challenge, pending["challenge"]):
                self.pending = None
                raise BridgeError("MFA_EXPIRED", 401)
            pending["attempts"] += 1
            pending["provider"].mfa(code)
            return self._finish_login(pending["provider"], pending["account"], pending["isCn"])

    def disconnect(self):
        with self.operation():
            self.pending = None
            self.vault.delete("session")
            return dict(connected=False)

    def sync(self):
        with self.operation():
            session = self.vault.get("session")
            if not session:
                raise BridgeError("AUTH_REQUIRED", 401)
            provider = self.factory()
            provider.restore(session["tokens"], session["isCn"])
            cache_key = "activities:" + session["account"]
            previous = self.vault.get(cache_key, dict(revision=0, activities={}, deletedIds=[]))
            activities = {}
            # Always inspect every page, including old modified activities. Bounded, no partial commits.
            for offset in range(0, 100000, 100):
                page = provider.page(offset, 100)
                if not isinstance(page, list) or len(page) > 100:
                    raise BridgeError("INCOMPLETE")
                for raw in page:
                    normalized = normalize(raw)
                    if normalized["id"] in activities:
                        # Offset pagination changed while scanning: retry later, don't infer deletions.
                        raise BridgeError("INCOMPLETE")
                    activities[normalized["id"]] = normalized
                if len(page) < 100:
                    break
            else:
                raise BridgeError("INCOMPLETE")
            deleted = set(previous["deletedIds"])
            for missing, cached in previous["activities"].items():
                if missing not in activities:
                    if provider.exists(missing):
                        # Concurrent pagination or a temporary omission. Retain prior data.
                        activities[missing] = cached
                    else:
                        deleted.add(missing)
            deleted.difference_update(activities)
            revision = previous["revision"] + 1
            result = dict(accountId=session["account"], revision=revision,
                activities=list(activities.values()), deletedIds=sorted(deleted), complete=True)
            # Persist refreshed tokens even after initial login; the SDK cannot write plaintext files.
            self.vault.put("session", dict(session, tokens=provider.tokens()))
            self.vault.put(cache_key, dict(revision=revision, activities=activities, deletedIds=sorted(deleted)))
            return result
