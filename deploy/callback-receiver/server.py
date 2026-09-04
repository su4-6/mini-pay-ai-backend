"""MiniPay merchant callback receiver with HMAC verification and idempotency."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

MAX_BODY_BYTES = 64 * 1024
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000
DATA_DIR = Path(os.getenv("DATA_DIR", "/data"))
SECRETS_FILE = DATA_DIR / "apps.json"
EVENTS_FILE = DATA_DIR / "events.jsonl"
ADMIN_TOKEN = os.environ["ADMIN_TOKEN"]
LOCK = threading.Lock()


def load_secrets() -> dict[str, str]:
    try:
        value = json.loads(SECRETS_FILE.read_text(encoding="utf-8"))
        return {str(key): str(secret) for key, secret in value.items()}
    except FileNotFoundError:
        return {}


def load_seen_events() -> set[str]:
    seen: set[str] = set()
    try:
        with EVENTS_FILE.open(encoding="utf-8") as records:
            for line in records:
                try:
                    event_id = str(json.loads(line).get("eventId") or "")
                    if event_id:
                        seen.add(event_id)
                except json.JSONDecodeError:
                    continue
    except FileNotFoundError:
        pass
    return seen


DATA_DIR.mkdir(parents=True, exist_ok=True)
SECRETS = load_secrets()
SEEN_EVENTS = load_seen_events()


def signing_source(payload: dict[str, object]) -> str:
    refund_no = payload.get("refundNo") or payload.get("refundBusinessNo")
    business_no = payload.get("paymentOrderNo") or payload.get("businessNo")
    parts = (
        ["REFUND", payload.get("appId"), payload.get("merchantOrderNo"), refund_no,
         business_no, payload.get("amountCent"), payload.get("timestamp"), payload.get("nonce")]
        if refund_no
        else ["PAYMENT", payload.get("appId"), payload.get("merchantOrderNo"),
              business_no, payload.get("amountCent"), payload.get("timestamp"), payload.get("nonce")]
    )
    if any(value is None or str(value) == "" for value in parts):
        raise ValueError("callback payload is incomplete")
    return "\n".join(str(value) for value in parts)


def save_secrets() -> None:
    temporary = SECRETS_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(SECRETS, ensure_ascii=False), encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(SECRETS_FILE)


class CallbackHandler(BaseHTTPRequestHandler):
    server_version = "MiniPayCallbackReceiver/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if urlparse(self.path).path == "/health":
            self.reply(200, {"status": "UP"})
            return
        self.reply(404, {"code": "NOT_FOUND"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"code": "INVALID_JSON"})
            return
        if path == "/internal/v1/apps":
            self.register_app(payload)
            return
        if path not in {"/payment/notify", "/refund/notify"}:
            self.reply(404, {"code": "NOT_FOUND"})
            return
        self.receive_callback(path, payload)

    def register_app(self, payload: dict[str, object]) -> None:
        authorization = self.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {ADMIN_TOKEN}"):
            self.reply(403, {"code": "FORBIDDEN"})
            return
        app_id = str(payload.get("appId") or "").strip()
        app_secret = str(payload.get("appSecret") or "")
        if not app_id or not app_secret:
            self.reply(422, {"code": "APP_ID_AND_SECRET_REQUIRED"})
            return
        with LOCK:
            SECRETS[app_id] = app_secret
            save_secrets()
        self.reply(200, {"code": "SUCCESS", "appId": app_id})

    def receive_callback(self, path: str, payload: dict[str, object]) -> None:
        app_id = str(payload.get("appId") or "")
        event_id = str(payload.get("eventId") or "")
        signature = self.headers.get("X-MiniPay-Signature", "")
        timestamp = self.headers.get("X-MiniPay-Timestamp", "")
        nonce = self.headers.get("X-MiniPay-Nonce", "")
        if str(payload.get("timestamp") or "") != timestamp or str(payload.get("nonce") or "") != nonce:
            self.reply(401, {"code": "SIGNED_HEADERS_MISMATCH"})
            return
        try:
            if abs(int(timestamp) - int(time.time() * 1000)) > MAX_CLOCK_SKEW_MS:
                self.reply(401, {"code": "TIMESTAMP_EXPIRED"})
                return
        except ValueError:
            self.reply(401, {"code": "TIMESTAMP_INVALID"})
            return
        with LOCK:
            secret = SECRETS.get(app_id)
        if not secret:
            self.reply(401, {"code": "APP_SECRET_NOT_REGISTERED"})
            return
        try:
            expected = hmac.new(secret.encode(), signing_source(payload).encode(), hashlib.sha256).hexdigest()
        except ValueError:
            self.reply(422, {"code": "PAYLOAD_INCOMPLETE"})
            return
        if not hmac.compare_digest(expected, signature):
            self.reply(401, {"code": "SIGNATURE_INVALID"})
            return
        event_type = str(payload.get("eventType") or "")
        if path == "/refund/notify" and not event_type.startswith("payment.refund"):
            self.reply(422, {"code": "EVENT_TYPE_MISMATCH"})
            return
        if path == "/payment/notify" and event_type.startswith("payment.refund"):
            self.reply(422, {"code": "EVENT_TYPE_MISMATCH"})
            return
        with LOCK:
            duplicate = bool(event_id and event_id in SEEN_EVENTS)
            if event_id and not duplicate:
                SEEN_EVENTS.add(event_id)
                record = {
                    "receivedAt": int(time.time() * 1000),
                    "eventId": event_id,
                    "eventType": event_type,
                    "appId": app_id,
                    "paymentOrderNo": payload.get("paymentOrderNo"),
                    "refundNo": payload.get("refundNo"),
                }
                with EVENTS_FILE.open("a", encoding="utf-8") as records:
                    records.write(json.dumps(record, ensure_ascii=False) + "\n")
        self.reply(200, {"code": "SUCCESS", "duplicate": duplicate})

    def read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("invalid body length")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("object expected")
        return value

    def reply(self, status: int, body: dict[str, object]) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, pattern: str, *args: object) -> None:
        print("callback-receiver", pattern % args, flush=True)


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8099"))
    ThreadingHTTPServer(("0.0.0.0", port), CallbackHandler).serve_forever()
