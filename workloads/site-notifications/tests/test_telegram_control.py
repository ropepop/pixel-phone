from pathlib import Path
import threading

import pytest

from state_store import StateStore
import telegram_control
from telegram_control import TelegramApiError, TelegramClient, TelegramCommandCallbacks, TelegramController


class FakeTelegramClient:
    def __init__(self, updates):
        self.updates = updates
        self.sent_messages = []

    def get_updates(self, offset=None, timeout=30):
        if offset is None:
            return self.updates
        return [u for u in self.updates if u["update_id"] >= offset]

    def send_message(self, chat_id, text, reply_markup=None, link_preview_enabled=False):
        self.sent_messages.append(
            {
                "chat_id": chat_id,
                "text": text,
                "reply_markup": reply_markup,
                "link_preview_enabled": link_preview_enabled,
            }
        )

class FlakySendTelegramClient(FakeTelegramClient):
    def __init__(self, updates):
        super().__init__(updates=updates)
        self.fail_once = True

    def send_message(self, chat_id, text, reply_markup=None, link_preview_enabled=False):
        if self.fail_once:
            self.fail_once = False
            raise TelegramApiError("send failed", error_code=500, status_code=500)
        super().send_message(
            chat_id,
            text,
            reply_markup=reply_markup,
            link_preview_enabled=link_preview_enabled,
        )


def _callbacks(calls):
    return TelegramCommandCallbacks(
        on_on=lambda: calls.append("on") or "on-ok",
        on_off=lambda: calls.append("off") or "off-ok",
        on_status=lambda: calls.append("status") or "status-ok",
        on_debug=lambda: calls.append("debug") or "debug-ok",
        on_checknow=lambda: calls.append("checknow") or "checknow-ok",
        on_reauth=lambda: calls.append("reauth") or "reauth-ok",
        on_help=lambda: "help-ok",
    )


def test_unauthorized_chat_is_ignored(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 1, "message": {"chat": {"id": 999}, "text": "/on"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    controller.poll_once(timeout=0)
    assert calls == []
    assert client.sent_messages == []
    assert store.load()["telegram_update_offset"] == 2


def test_status_command_from_authorized_chat(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 7, "message": {"chat": {"id": 123}, "text": "/status"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    controller.poll_once(timeout=0)
    assert calls == ["status"]
    assert client.sent_messages[0]["chat_id"] == 123
    assert client.sent_messages[0]["text"] == "status-ok"
    assert client.sent_messages[0]["reply_markup"] is not None
    assert store.load()["telegram_update_offset"] == 8


def test_reauth_command_from_authorized_chat(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 11, "message": {"chat": {"id": 123}, "text": "/reauth"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    controller.poll_once(timeout=0)
    assert calls == ["reauth"]
    assert client.sent_messages[0]["chat_id"] == 123
    assert client.sent_messages[0]["text"] == "reauth-ok"
    assert store.load()["telegram_update_offset"] == 12


def test_debug_command_from_authorized_chat(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 15, "message": {"chat": {"id": 123}, "text": "/debug"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    controller.poll_once(timeout=0)
    assert calls == ["debug"]
    assert client.sent_messages[0]["text"] == "debug-ok"


def test_button_alias_dispatches_as_command(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 13, "message": {"chat": {"id": 123}, "text": "Check now"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    controller.poll_once(timeout=0)
    assert calls == ["checknow"]
    assert client.sent_messages[0]["text"] == "checknow-ok"


def test_unknown_text_returns_hint(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FakeTelegramClient(
        updates=[
            {"update_id": 14, "message": {"chat": {"id": 123}, "text": "what now?"}},
        ]
    )
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks([]),
    )

    controller.poll_once(timeout=0)
    assert "Unknown command" in client.sent_messages[0]["text"]


def test_offset_advances_before_send_failure_to_prevent_replay(tmp_path: Path):
    store = StateStore(tmp_path / "state.json")
    client = FlakySendTelegramClient(
        updates=[
            {"update_id": 22, "message": {"chat": {"id": 123}, "text": "/status"}},
        ]
    )
    calls = []
    controller = TelegramController(
        client=client,
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks(calls),
    )

    with pytest.raises(TelegramApiError):
        controller.poll_once(timeout=0)

    # Offset must be persisted even when send fails.
    assert store.load()["telegram_update_offset"] == 23
    assert calls == ["status"]

    # Next poll should not replay update_id=22.
    controller.poll_once(timeout=0)
    assert calls == ["status"]


def test_run_forever_recovers_from_unexpected_exceptions(tmp_path: Path, monkeypatch):
    store = StateStore(tmp_path / "state.json")
    controller = TelegramController(
        client=FakeTelegramClient(updates=[]),
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks([]),
    )
    stop_event = threading.Event()
    loop_calls: list[int] = []
    iteration_calls: list[str] = []
    poll_errors: list[str] = []

    def flaky_poll_once(timeout=30):
        loop_calls.append(timeout)
        if len(loop_calls) == 1:
            raise RuntimeError("boom")
        stop_event.set()

    monkeypatch.setattr(controller, "poll_once", flaky_poll_once)
    monkeypatch.setattr(telegram_control.time, "sleep", lambda _seconds: None)
    controller.run_forever(
        stop_event=stop_event,
        on_iteration=lambda: iteration_calls.append("ok"),
        on_poll_error=lambda exc: poll_errors.append(str(exc)),
    )

    assert len(loop_calls) == 2
    assert iteration_calls == ["ok", "ok"]
    assert poll_errors == ["boom"]


def test_run_forever_reports_telegram_api_errors(tmp_path: Path, monkeypatch):
    store = StateStore(tmp_path / "state.json")
    controller = TelegramController(
        client=FakeTelegramClient(updates=[]),
        state_store=store,
        authorized_chat_id=123,
        callbacks=_callbacks([]),
    )
    stop_event = threading.Event()
    iteration_calls: list[str] = []
    poll_errors: list[str] = []
    calls = 0

    def flaky_poll_once(timeout=30):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise TelegramApiError("network down")
        stop_event.set()

    monkeypatch.setattr(controller, "poll_once", flaky_poll_once)
    monkeypatch.setattr(telegram_control.time, "sleep", lambda _seconds: None)
    controller.run_forever(
        stop_event=stop_event,
        on_iteration=lambda: iteration_calls.append("tick"),
        on_poll_error=lambda exc: poll_errors.append(str(exc)),
    )

    assert iteration_calls == ["tick", "tick"]
    assert poll_errors == ["network down"]


def test_telegram_client_reports_webhook_conflict(monkeypatch: pytest.MonkeyPatch):
    class FakeResponse:
        status_code = 200
        ok = True

        @staticmethod
        def json():
            return {
                "ok": False,
                "error_code": 409,
                "description": "Conflict: can't use getUpdates method while webhook is active",
            }

    client = TelegramClient(token="TOKEN")
    monkeypatch.setattr(client.session, "post", lambda *args, **kwargs: FakeResponse())

    with pytest.raises(TelegramApiError) as exc:
        client.get_updates(offset=None, timeout=0)

    assert exc.value.error_code == 409
    assert "webhook" in str(exc.value).lower()


def test_telegram_client_retries_send_message_on_429(monkeypatch: pytest.MonkeyPatch):
    class FakeResponse:
        def __init__(self, payload: dict):
            self._payload = payload
            self.status_code = 200
            self.ok = True

        def json(self):
            return self._payload

    responses = iter(
        [
            FakeResponse(
                {
                    "ok": False,
                    "error_code": 429,
                    "description": "Too Many Requests",
                    "parameters": {"retry_after": 0},
                }
            ),
            FakeResponse({"ok": True, "result": {"message_id": 1}}),
        ]
    )

    client = TelegramClient(token="TOKEN")
    monkeypatch.setattr(client.session, "post", lambda *args, **kwargs: next(responses))
    monkeypatch.setattr(telegram_control.time, "sleep", lambda _seconds: None)

    client.send_message(chat_id=1, text="ok")


def test_telegram_client_disables_link_preview_by_default(monkeypatch: pytest.MonkeyPatch):
    captured_payloads: list[dict] = []

    class FakeResponse:
        status_code = 200
        ok = True

        @staticmethod
        def json():
            return {"ok": True, "result": {"message_id": 1}}

    def _post(*_args, **kwargs):
        captured_payloads.append(kwargs["json"])
        return FakeResponse()

    client = TelegramClient(token="TOKEN")
    monkeypatch.setattr(client.session, "post", _post)

    client.send_message(chat_id=1, text="hello")

    assert captured_payloads[0]["link_preview_options"] == {"is_disabled": True}


def test_telegram_client_can_enable_link_preview(monkeypatch: pytest.MonkeyPatch):
    captured_payloads: list[dict] = []

    class FakeResponse:
        status_code = 200
        ok = True

        @staticmethod
        def json():
            return {"ok": True, "result": {"message_id": 2}}

    def _post(*_args, **kwargs):
        captured_payloads.append(kwargs["json"])
        return FakeResponse()

    client = TelegramClient(token="TOKEN")
    monkeypatch.setattr(client.session, "post", _post)

    client.send_message(chat_id=1, text="hello", link_preview_enabled=True)

    assert captured_payloads[0]["link_preview_options"] == {"is_disabled": False}
