"""Local-only MiniPay callback receiver with signature and retry test modes."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

SECRETS: dict[str, str] = {}
RECORDS: list[dict[str, object]] = []
SEEN_EVENTS: set[str] = set()
LOCK = threading.Lock()


def signing_source(payload: dict[str, object]) -> str:
    refund_no = payload.get("refundNo") or payload.get("refundBusinessNo")
    parts = (
        ["REFUND", payload.get("appId"), payload.get("merchantOrderNo"), refund_no,
         payload.get("paymentOrderNo"), payload.get("amountCent"), payload.get("timestamp"), payload.get("nonce")]
        if refund_no
        else ["PAYMENT", payload.get("appId"), payload.get("merchantOrderNo"),
              payload.get("paymentOrderNo"), payload.get("amountCent"), payload.get("timestamp"), payload.get("nonce")]
    )
    if any(value is None or str(value) == "" for value in parts):
        raise ValueError("callback payload is incomplete")
    return "\n".join(str(value) for value in parts)


class CallbackHandler(BaseHTTPRequestHandler):
    server_version = "MiniPayCallbackMock/1.0"

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self.reply(200, {"status": "UP"})
            return
        if path == "/v1/records":
            with LOCK:
                self.reply(200, {"items": list(reversed(RECORDS[-200:]))})
            return
        self.reply(404, {"code": "NOT_FOUND"})

    def do_DELETE(self) -> None:  # noqa: N802
        if urlparse(self.path).path != "/v1/records":
            self.reply(404, {"code": "NOT_FOUND"})
            return
        with LOCK:
            RECORDS.clear()
            SEEN_EVENTS.clear()
        self.reply(200, {"code": "SUCCESS"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"code": "INVALID_JSON"})
            return
        if path == "/v1/secrets":
            app_id = str(payload.get("appId") or "")
            secret = str(payload.get("appSecret") or "")
            if not app_id or not secret:
                self.reply(422, {"code": "APP_ID_AND_SECRET_REQUIRED"})
                return
            with LOCK:
                SECRETS[app_id] = secret
            self.reply(200, {"code": "SUCCESS", "appId": app_id})
            return
        if not path.startswith("/callbacks/"):
            self.reply(404, {"code": "NOT_FOUND"})
            return
        self.receive_callback(path, payload)

    def receive_callback(self, path: str, payload: dict[str, object]) -> None:
        mode = path.rsplit("/", 1)[-1]
        app_id = str(payload.get("appId") or "")
        event_id = str(payload.get("eventId") or "")
        signature = self.headers.get("X-MiniPay-Signature", "")
        timestamp = self.headers.get("X-MiniPay-Timestamp", "")
        nonce = self.headers.get("X-MiniPay-Nonce", "")
        payload["timestamp"] = payload.get("timestamp") or timestamp
        payload["nonce"] = payload.get("nonce") or nonce
        with LOCK:
            secret = SECRETS.get(app_id)
        valid = False
        error = None
        if not secret:
            error = "SECRET_NOT_REGISTERED"
        else:
            try:
                expected = hmac.new(secret.encode(), signing_source(payload).encode(), hashlib.sha256).hexdigest()
                valid = hmac.compare_digest(expected, signature)
                if not valid:
                    error = "SIGNATURE_INVALID"
            except ValueError as exc:
                error = str(exc)
        with LOCK:
            duplicate = event_id in SEEN_EVENTS
            if event_id:
                SEEN_EVENTS.add(event_id)
            RECORDS.append({
                "receivedAt": int(time.time() * 1000), "path": path, "mode": mode,
                "eventId": event_id, "eventType": payload.get("eventType"), "appId": app_id,
                "signatureValid": valid, "duplicate": duplicate, "error": error,
                "paymentOrderNo": payload.get("paymentOrderNo"), "refundNo": payload.get("refundNo")
            })
        if not valid:
            self.reply(401, {"code": error or "SIGNATURE_INVALID"})
            return
        if mode == "timeout":
            time.sleep(6)
        if mode == "fail":
            self.reply(500, {"code": "TEMPORARY_FAILURE"})
            return
        self.reply(200, {"code": "SUCCESS", "duplicate": duplicate})

    def read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        value = json.loads(body or "{}")
        if not isinstance(value, dict):
            raise ValueError("object expected")
        return value

    def reply(self, status: int, body: dict[str, object]) -> None:
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, pattern: str, *args: object) -> None:
        print("callback-mock", pattern % args, flush=True)


if __name__ == "__main__":
    port = int(os.getenv("PORT", "8098"))
    ThreadingHTTPServer(("0.0.0.0", port), CallbackHandler).serve_forever()
