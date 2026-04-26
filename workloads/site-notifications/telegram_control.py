from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Callable

import requests

from state_store import StateStore


LOG = logging.getLogger("gribu_notifier")
NAV_BUTTON_ROWS = (
    ("Enable", "Pause"),
    ("Status", "Check now"),
    ("Reauth", "Help"),
)
BUTTON_COMMAND_ALIASES = {
    "enable": "/on",
    "pause": "/off",
    "status": "/status",
    "check now": "/checknow",
    "reauth": "/reauth",
    "help": "/help",
}
UNKNOWN_COMMAND_MESSAGE = "Unknown command. Use the buttons below or send /help."


def build_navigation_reply_markup() -> dict[str, Any]:
    return {
        "keyboard": [list(row) for row in NAV_BUTTON_ROWS],
        "resize_keyboard": True,
        "is_persistent": True,
        "one_time_keyboard": False,
        "input_field_placeholder": "Choose an action",
    }


class TelegramApiError(Exception):
    def __init__(
        self,
        message: str,
        *,
        method: str | None = None,
        status_code: int | None = None,
        error_code: int | None = None,
        description: str | None = None,
        retry_after_sec: int | None = None,
        retriable: bool = False,
    ) -> None:
        super().__init__(message)
        self.method = method
        self.status_code = status_code
        self.error_code = error_code
        self.description = description
        self.retry_after_sec = retry_after_sec
        self.retriable = retriable


@dataclass(frozen=True)
class TelegramCommandCallbacks:
    on_on: Callable[[], str]
    on_off: Callable[[], str]
    on_status: Callable[[], str]
    on_debug: Callable[[], str]
    on_checknow: Callable[[], str]
    on_reauth: Callable[[], str]
    on_help: Callable[[], str]


class TelegramClient:
    def __init__(self, token: str, timeout_sec: int = 20, api_base_url: str = "https://api.telegram.org"):
        self.token = token
        self.timeout_sec = timeout_sec
        self.api_base_url = api_base_url.rstrip("/")
        self.session = requests.Session()
        self.send_max_retries = 2
        self.send_retry_backoff_sec = 0.5

    @property
    def _base(self) -> str:
        return f"{self.api_base_url}/bot{self.token}"

    @staticmethod
    def _extract_telegram_error(data: dict[str, Any]) -> tuple[int | None, str | None, int | None]:
        error_code_raw = data.get("error_code")
        error_code = error_code_raw if isinstance(error_code_raw, int) else None
        description_raw = data.get("description")
        description = description_raw if isinstance(description_raw, str) else None
        params = data.get("parameters")
        retry_after_sec: int | None = None
        if isinstance(params, dict):
            retry_after_raw = params.get("retry_after")
            if isinstance(retry_after_raw, int):
                retry_after_sec = retry_after_raw
        return error_code, description, retry_after_sec

    def _request_json(
        self,
        *,
        method: str,
        payload: dict[str, Any] | None = None,
        timeout: int | float | None = None,
    ) -> dict[str, Any]:
        request_timeout = self.timeout_sec if timeout is None else timeout
        try:
            response = self.session.post(
                f"{self._base}/{method}",
                json=payload or {},
                timeout=request_timeout,
            )
        except requests.RequestException as exc:
            raise TelegramApiError(
                f"Failed Telegram {method} request: {exc}",
                method=method,
                retriable=True,
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise TelegramApiError(
                f"Failed to decode Telegram {method} response as JSON (http={response.status_code})",
                method=method,
                status_code=response.status_code,
                retriable=response.status_code >= 500,
            ) from exc

        error_code, description, retry_after_sec = self._extract_telegram_error(data)
        retriable = response.status_code >= 500 or response.status_code == 429
        if not response.ok:
            raise TelegramApiError(
                f"Telegram {method} HTTP error {response.status_code}: {description or data}",
                method=method,
                status_code=response.status_code,
                error_code=error_code,
                description=description,
                retry_after_sec=retry_after_sec,
                retriable=retriable,
            )
        if not data.get("ok"):
            message = f"Telegram returned non-ok {method} response: {data}"
            if method == "getUpdates" and error_code == 409:
                message = (
                    "Telegram getUpdates conflict (error 409). "
                    "A webhook is likely configured; disable webhook to use polling."
                )
            raise TelegramApiError(
                message,
                method=method,
                status_code=response.status_code,
                error_code=error_code,
                description=description,
                retry_after_sec=retry_after_sec,
                retriable=error_code == 429,
            )
        return data

    def get_updates(self, offset: int | None, timeout: int = 30) -> list[dict[str, Any]]:
        payload: dict[str, Any] = {
            "timeout": timeout,
            "allowed_updates": ["message"],
        }
        if offset is not None:
            payload["offset"] = offset
        data = self._request_json(
            method="getUpdates",
            payload=payload,
            timeout=self.timeout_sec + timeout,
        )
        return data.get("result", [])

    def send_message(
        self,
        chat_id: int,
        text: str,
        reply_markup: dict[str, Any] | None = None,
        *,
        link_preview_enabled: bool = False,
    ) -> None:
        payload = {
            "chat_id": chat_id,
            "text": text,
            "link_preview_options": {
                "is_disabled": not link_preview_enabled,
            },
        }
        if reply_markup is not None:
            payload["reply_markup"] = reply_markup
        attempt = 0
        max_attempts = self.send_max_retries + 1
        while attempt < max_attempts:
            attempt += 1
            try:
                self._request_json(
                    method="sendMessage",
                    payload=payload,
                    timeout=self.timeout_sec,
                )
                return
            except TelegramApiError as exc:
                should_retry = exc.retriable and attempt < max_attempts
                if not should_retry:
                    raise
                backoff_sec = exc.retry_after_sec if exc.retry_after_sec else self.send_retry_backoff_sec * (
                    2 ** (attempt - 1)
                )
                LOG.warning(
                    "Retrying sendMessage after error (attempt=%s/%s, backoff=%.2fs): %s",
                    attempt,
                    max_attempts,
                    backoff_sec,
                    exc,
                )
                time.sleep(max(0.0, float(backoff_sec)))

    def get_me(self) -> dict[str, Any]:
        return self._request_json(method="getMe").get("result", {})

    def get_webhook_info(self) -> dict[str, Any]:
        return self._request_json(method="getWebhookInfo").get("result", {})


class TelegramController:
    def __init__(
        self,
        client: TelegramClient,
        state_store: StateStore,
        authorized_chat_id: int,
        callbacks: TelegramCommandCallbacks,
        navigation_buttons_enabled: bool = True,
    ):
        self.client = client
        self.state_store = state_store
        self.authorized_chat_id = authorized_chat_id
        self.callbacks = callbacks
        self.navigation_buttons_enabled = navigation_buttons_enabled

    def _reply_markup(self) -> dict[str, Any] | None:
        if not self.navigation_buttons_enabled:
            return None
        return build_navigation_reply_markup()

    def _normalize_command(self, text: str) -> str:
        cleaned = (text or "").strip()
        if not cleaned:
            return ""
        if cleaned.startswith("/"):
            first_token = cleaned.split()[0]
            first_token = first_token.split("@", 1)[0]
            return first_token.lower()
        return BUTTON_COMMAND_ALIASES.get(cleaned.lower(), "")

    def _dispatch(self, command: str) -> str | None:
        if command == "/on":
            return self.callbacks.on_on()
        if command == "/off":
            return self.callbacks.on_off()
        if command == "/status":
            return self.callbacks.on_status()
        if command == "/debug":
            return self.callbacks.on_debug()
        if command == "/checknow":
            return self.callbacks.on_checknow()
        if command == "/reauth":
            return self.callbacks.on_reauth()
        if command == "/help":
            return self.callbacks.on_help()
        return None

    def _record_command_latency(self, latency_ms: int) -> None:
        self.state_store.patch({"last_command_latency_ms": max(0, latency_ms)})
        if latency_ms > 3000:
            LOG.warning("Telegram command latency high (sync command path): %sms", latency_ms)

    def _send_response(self, text: str) -> None:
        started = time.monotonic()
        try:
            self.client.send_message(
                chat_id=self.authorized_chat_id,
                text=text,
                reply_markup=self._reply_markup(),
            )
            latency_ms = int((time.monotonic() - started) * 1000)
            self.state_store.patch(
                {
                    "telegram_send_latency_ms": max(0, latency_ms),
                    "last_telegram_api_error_code": None,
                }
            )
        except TelegramApiError as exc:
            latency_ms = int((time.monotonic() - started) * 1000)
            self.state_store.patch(
                {
                    "telegram_send_latency_ms": max(0, latency_ms),
                    "last_telegram_api_error_code": exc.error_code or exc.status_code,
                }
            )
            raise

    def poll_once(self, timeout: int = 30) -> None:
        state = self.state_store.load()
        offset = state.get("telegram_update_offset")
        started_updates = time.monotonic()
        updates = self.client.get_updates(offset=offset, timeout=timeout)
        updates_latency_ms = int((time.monotonic() - started_updates) * 1000)
        self.state_store.patch({"telegram_getupdates_latency_ms": max(0, updates_latency_ms)})
        for update in updates:
            update_id = update.get("update_id")
            if isinstance(update_id, int):
                # Advance offset as soon as update is claimed so delivery/dispatch failures
                # cannot poison-replay the same command indefinitely.
                self.state_store.patch({"telegram_update_offset": update_id + 1})

            message = update.get("message") or {}
            text = message.get("text", "")
            chat_id = message.get("chat", {}).get("id")
            command = self._normalize_command(text)

            is_authorized = isinstance(chat_id, int) and chat_id == self.authorized_chat_id
            if is_authorized:
                if command:
                    started = time.monotonic()
                    response = self._dispatch(command)
                    latency_ms = int((time.monotonic() - started) * 1000)
                    self._record_command_latency(latency_ms)
                    if response:
                        self._send_response(response)
                    else:
                        self._send_response(UNKNOWN_COMMAND_MESSAGE)
                elif isinstance(text, str) and text.strip():
                    self._send_response(UNKNOWN_COMMAND_MESSAGE)

    def run_forever(
        self,
        stop_event,
        on_iteration: Callable[[], None] | None = None,
        on_poll_error: Callable[[Exception], None] | None = None,
    ) -> None:
        while not stop_event.is_set():
            try:
                self.poll_once(timeout=30)
            except TelegramApiError as exc:
                LOG.warning(
                    "Telegram polling failed: %s (method=%s status=%s error_code=%s retriable=%s)",
                    exc,
                    getattr(exc, "method", None),
                    getattr(exc, "status_code", None),
                    getattr(exc, "error_code", None),
                    getattr(exc, "retriable", None),
                )
                if on_poll_error is not None:
                    on_poll_error(exc)
                time.sleep(2.0)
            except Exception as exc:
                LOG.exception("Unexpected error in Telegram polling loop")
                if on_poll_error is not None:
                    on_poll_error(exc)
                time.sleep(2.0)
            finally:
                if on_iteration is not None:
                    on_iteration()
