"""Generate bridge secrets without printing them or putting Garmin credentials in env."""
import argparse
from pathlib import Path
import os
import secrets
import base64

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", required=True)
    args = parser.parse_args()
    if not args.domain or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789.-" for c in args.domain):
        parser.error("A DNS hostname is required")
    path = Path("secrets.env")
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write(f"GARMIN_BRIDGE_API_KEY={secrets.token_urlsafe(48)}\nGARMIN_BRIDGE_MASTER_KEY={base64.urlsafe_b64encode(secrets.token_bytes(32)).decode()}\nDOMAIN={args.domain}\n")
    print("Configuration créée dans secrets.env (permissions 0600). Conservez une sauvegarde privée.")
