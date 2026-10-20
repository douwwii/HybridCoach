import logging
import os
import secrets
from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, Field, SecretStr
from garminconnect import GarminConnectAuthenticationError, GarminConnectTooManyRequestsError
from .core import Bridge, BridgeError, Vault


class LoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=320)
    password: SecretStr = Field(min_length=1, max_length=1024)
    isCn: bool = False


class MfaRequest(BaseModel):
    challengeId: str = Field(min_length=16, max_length=128)
    code: SecretStr = Field(min_length=1, max_length=32)


def create_app(bridge, api_key):
    if len(api_key) < 32:
        raise ValueError("GARMIN_BRIDGE_API_KEY must contain at least 32 characters")
    # SDK logs must never contain response bodies, SSO tickets or token material.
    logging.getLogger("garminconnect").disabled = True
    for name in list(logging.Logger.manager.loggerDict):
        if name.startswith("garminconnect."):
            logging.getLogger(name).disabled = True
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    def authorize(request: Request):
        if not secrets.compare_digest(request.headers.get("authorization", ""), "Bearer " + api_key):
            raise BridgeError("BRIDGE_AUTH", 401)

    @app.middleware("http")
    async def limit_requests(request, call_next):
        try:
            length = int(request.headers.get("content-length", "0") or "0")
        except ValueError:
            return JSONResponse(dict(code="BAD_REQUEST"), status_code=400)
        if length > 16384:
            return JSONResponse(dict(code="REQUEST_TOO_LARGE"), status_code=413)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return JSONResponse(dict(code="BAD_REQUEST"), status_code=400)

    @app.exception_handler(BridgeError)
    async def bridge_error(request, error):
        return JSONResponse(dict(code=error.code), status_code=error.status)

    @app.exception_handler(Exception)
    async def unknown_error(request, error):
        # No raw exception strings: requests can embed credential-bearing URLs.
        return JSONResponse(dict(code="UPSTREAM"), status_code=502)

    def guarded(operation):
        try:
            return operation()
        except GarminConnectAuthenticationError:
            bridge.disconnect()
            raise BridgeError("AUTH_REQUIRED", 401) from None
        except GarminConnectTooManyRequestsError:
            raise BridgeError("RATE_LIMIT", 429) from None
        except BridgeError:
            raise
        except Exception:
            raise BridgeError("UPSTREAM", 502) from None

    @app.get("/health")
    def health():
        return dict(ok=True)

    @app.get("/v1/status", dependencies=[Depends(authorize)])
    def status():
        return bridge.status()

    @app.post("/v1/login", dependencies=[Depends(authorize)])
    def login(body: LoginRequest):
        try:
            return bridge.login(body.email, body.password.get_secret_value(), body.isCn)
        except GarminConnectAuthenticationError:
            raise BridgeError("BAD_CREDENTIALS", 401) from None
        except GarminConnectTooManyRequestsError:
            raise BridgeError("RATE_LIMIT", 429) from None
        except BridgeError:
            raise
        except Exception:
            raise BridgeError("UPSTREAM", 502) from None

    @app.post("/v1/mfa", dependencies=[Depends(authorize)])
    def mfa(body: MfaRequest):
        try:
            return bridge.mfa(body.challengeId, body.code.get_secret_value())
        except GarminConnectAuthenticationError:
            raise BridgeError("BAD_CREDENTIALS", 401) from None
        except BridgeError:
            raise
        except Exception:
            raise BridgeError("UPSTREAM", 502) from None

    @app.delete("/v1/session", dependencies=[Depends(authorize)])
    def disconnect():
        return guarded(bridge.disconnect)

    @app.post("/v1/sync", dependencies=[Depends(authorize)])
    def sync():
        return guarded(bridge.sync)

    return app


def configured_app():
    api_key = os.environ["GARMIN_BRIDGE_API_KEY"]
    vault = Vault(os.getenv("GARMIN_BRIDGE_DB", "/data/garmin.db"), os.environ["GARMIN_BRIDGE_MASTER_KEY"])
    return create_app(Bridge(vault, os.environ["GARMIN_BRIDGE_MASTER_KEY"]), api_key)
