import json
from datetime import datetime, timezone
from pathlib import Path

from app import NotifierService
from config import Config
from gribu_auth import GribuAuthError
from gribu_client import GribuClientError, GribuResponse
from state_store import StateStore


class FakeGribuClient:
    def __init__(self, responses=None, by_url=None, errors_by_url=None, api_by_url=None, api_errors_by_url=None):
        self.responses = list(responses or [])
        self.by_url = {
            key: (list(value) if isinstance(value, list) else [value])
            for key, value in (by_url or {}).items()
        }
        self.errors_by_url = {
            key: (list(value) if isinstance(value, list) else [value])
            for key, value in (errors_by_url or {}).items()
        }
        self.api_by_url = {
            key: (list(value) if isinstance(value, list) else [value])
            for key, value in (api_by_url or {}).items()
        }
        self.api_errors_by_url = {
            key: (list(value) if isinstance(value, list) else [value])
            for key, value in (api_errors_by_url or {}).items()
        }
        self.cookie_header = "DATED=1; DATINGSES=1"
        self.calls: list[str] = []
        self.api_calls: list[str] = []

    def fetch_check_page(self, check_url):
        self.calls.append(check_url)

        error_bucket = self.errors_by_url.get(check_url)
        if error_bucket:
            if len(error_bucket) > 1:
                maybe_error = error_bucket.pop(0)
            else:
                maybe_error = error_bucket[0]
            if isinstance(maybe_error, Exception):
                raise maybe_error
            raise GribuClientError(str(maybe_error))

        url_bucket = self.by_url.get(check_url)
        if url_bucket:
            if len(url_bucket) > 1:
                return url_bucket.pop(0)
            return url_bucket[0]

        if check_url == "/cats":
            return GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_absent_html(),
            )

        if not self.responses:
            raise AssertionError(f"No fake responses left for route {check_url!r}")
        if len(self.responses) > 1:
            return self.responses.pop(0)
        return self.responses[0]

    def export_cookie_header(self):
        return self.cookie_header

    def post_form(self, path, data=None, headers=None):
        del data, headers
        self.api_calls.append(path)

        error_bucket = self.api_errors_by_url.get(path)
        if error_bucket:
            if len(error_bucket) > 1:
                maybe_error = error_bucket.pop(0)
            else:
                maybe_error = error_bucket[0]
            if isinstance(maybe_error, Exception):
                raise maybe_error
            raise GribuClientError(str(maybe_error))

        url_bucket = self.api_by_url.get(path)
        if url_bucket:
            if len(url_bucket) > 1:
                return url_bucket.pop(0)
            return url_bucket[0]

        raise AssertionError(f"No fake API responses left for path {path!r}")


class FakeGribuAuthenticator:
    def __init__(self, outcomes=None):
        self.outcomes = list(outcomes or [])
        self.calls = []

    def authenticate(self, login_id, login_password):
        self.calls.append((login_id, login_password))
        if not self.outcomes:
            return
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome


class FakeTelegramClient:
    def __init__(self):
        self.sent = []

    def send_message(self, chat_id, text, reply_markup=None, link_preview_enabled=False):
        self.sent.append(
            {
                "chat_id": chat_id,
                "text": text,
                "reply_markup": reply_markup,
                "link_preview_enabled": link_preview_enabled,
            }
        )


def _make_config(
    tmp_path: Path,
    *,
    check_url: str = "/lv/messages",
    check_url_candidates: tuple[str, ...] = ("/lv/messages",),
    preview_url: str = "/cats",
    preview_url_candidates: tuple[str, ...] = ("/cats",),
    parse_min_confidence_baseline: float = 0.8,
    parse_min_confidence_update: float = 0.7,
    parse_min_confidence_route_selection: float = 0.7,
) -> Config:
    return Config(
        telegram_bot_token="token",
        telegram_chat_id=111,
        gribu_base_url="https://www.gribu.lv",
        gribu_check_url=check_url,
        gribu_check_url_candidates=check_url_candidates,
        gribu_preview_url=preview_url,
        gribu_preview_url_candidates=preview_url_candidates,
        gribu_login_id="demo@example.com",
        gribu_login_password="secret",
        gribu_login_path="/pieslegties",
        gribu_cookie_header="a=b",
        check_interval_sec=60,
        check_interval_fast_sec=20,
        check_interval_idle_sec=60,
        check_interval_error_backoff_max_sec=180,
        state_file=(tmp_path / "state.json"),
        http_timeout_sec=10,
        error_alert_cooldown_sec=1800,
        telegram_api_base_url="https://api.telegram.org",
        telegram_nav_buttons_enabled=True,
        env_file_path=(tmp_path / ".env"),
        daemon_lock_file=(tmp_path / "daemon.lock"),
        watchdog_check_sec=10,
        watchdog_stale_sec=120,
        supervisor_restart_base_sec=2,
        supervisor_restart_max_sec=30,
        parse_low_confidence_delta_limit=20,
        route_discovery_ttl_sec=21600,
        preview_route_discovery_ttl_sec=21600,
        parse_min_confidence_baseline=parse_min_confidence_baseline,
        parse_min_confidence_update=parse_min_confidence_update,
        parse_min_confidence_route_selection=parse_min_confidence_route_selection,
    )


def _prime_route(store: StateStore, check_url: str = "/lv/messages", preview_url: str = "/cats") -> None:
    store.patch(
        {
            "resolved_check_url": check_url,
            "route_discovery_last_ts": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "route_discovery_last_result": f"selected:{check_url}:seed:1.00",
            "route_discovery_last_candidates": [],
            "resolved_preview_url": preview_url,
            "preview_route_discovery_last_ts": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "preview_route_discovery_last_result": f"selected:{preview_url}:seed:1",
            "preview_route_discovery_last_candidates": [],
        }
    )


def _prime_preview_route(store: StateStore, preview_url: str) -> None:
    store.patch(
        {
            "resolved_preview_url": preview_url,
            "preview_route_discovery_last_ts": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "preview_route_discovery_last_result": f"selected:{preview_url}:seed:1",
            "preview_route_discovery_last_candidates": [],
        }
    )


def _message_html(count: int) -> str:
    return f'<div data-header-notification-count data-count="{count}"></div>'


def _message_list_html(count: int, *rows: tuple[str, str, str]) -> str:
    items = "".join(
        (
            "<li class='messages-list-item' data-message-id='{idx}'>"
            "<a class='messages-list-item__link' href='{href}'>"
            "<span class='messages-list-item__title'>{title}</span>"
            "<span class='messages-list-item__preview'>{preview}</span>"
            "</a>"
            "</li>"
        ).format(idx=index, href=href, title=title, preview=preview)
        for index, (href, title, preview) in enumerate(rows, start=1)
    )
    return (
        "<html><body>"
        f"<div data-header-notification-count data-count=\"{count}\"></div>"
        "<ul class='messages-list'>"
        f"{items}"
        "</ul>"
        "</body></html>"
    )


def _chat_badge_menu_html(count: int) -> str:
    return (
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link'>"
        "<span class='header__counter-inner'>"
        "<span class='online-counter online-counter_header'>"
        f"<sup class='online-counter__count'>{count}</sup>"
        "</span>"
        "</span>"
        "</a>"
        "</li>"
    )


def _chat_badge_absent_html() -> str:
    return (
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link _active'>"
        "<span class='header__bottom-row-name_optional'>Čats</span>"
        "</a>"
        "</li>"
    )


def _cats_contact_list_html(total_unread: int, *rows: tuple[str, int, str, str]) -> str:
    items = "".join(
        (
            "<li class='chat-contact-list-item' data-conversation-id='conv-{idx}'>"
            "<a class='chat-contact-list-item__link' href='{href}'>"
            "<span class='chat-contact-list-item__title'>{title}</span>"
            "{badge}"
            "<span class='chat-contact-list-item__preview'>{preview}</span>"
            "</a>"
            "</li>"
        ).format(
            idx=index,
            href=href,
            title=title,
            preview=preview,
            badge=(
                ""
                if unread_count <= 0
                else (
                    "<span class='chat-contact-list-item__badge'>"
                    f"<span class='online-counter__count'>{unread_count}</span>"
                    "</span>"
                )
            ),
        )
        for index, (href, unread_count, title, preview) in enumerate(rows, start=1)
    )
    return (
        "<html><body>"
        f"{_chat_badge_menu_html(total_unread)}"
        "<nav>"
        "<a href='/cats' class='header__bottom-row-link'>Chat</a>"
        "<a href='/en/chat' class='header__bottom-row-link'>Forum</a>"
        "</nav>"
        "<ul class='chat-contact-list'>"
        f"{items}"
        "</ul>"
        "</body></html>"
    )


def _chat_shell_html(count: int, *, locale: str = "en") -> str:
    language_text = "Choose language: English Latvian Russian Lithuanian"
    nav_text = (
        "Chat Dating Advertisements Search Hot or Not Last Minute Photos "
        "Video Groups Stories Blog Events Ratings Live Sex News Forum For Fans More"
    )
    return (
        f"<html lang='{locale}'><body>"
        f"{_chat_badge_menu_html(count)}"
        "<nav>"
        f"<a href='/{locale}/chat'>{language_text}</a>"
        f"<a href='/{locale}/chat'>{nav_text}</a>"
        "</nav>"
        f"<div id='chat-app' data-user-id='1791194' data-current-locale='lv, en, ru' "
        f"data-wss-url='wss://chat.gribu.lv' data-token='token-{locale}'></div>"
        "</body></html>"
    )


def _chat_api_conversations_response(*rows: tuple[str, str, str]) -> GribuResponse:
    payload = {
        "conversations": [
            {
                "id": index,
                "conversationId": conversation_id,
                "name": title,
                "username": title,
                "newMessages": 0,
                "isRoom": False,
                "isAdmin": False,
                "isShoutbox": False,
                "lastMessage": {
                    "id": f"msg-{index}",
                    "createdAt": f"2026-03-08 0{index}:00:00",
                    "text": text,
                    "isMe": False,
                    "isRead": False,
                },
            }
            for index, (conversation_id, title, text) in enumerate(rows, start=1)
        ],
        "counts": {"all": None, "personal": str(len(rows)), "group": "0"},
        "user": {},
        "usersOnlineInfo": {},
    }
    return GribuResponse(
        status_code=200,
        url="https://www.gribu.lv/api/en/conversations/1",
        text=json.dumps(payload),
    )


def _keyword_message_html(count: int) -> str:
    return f"<html><body><p>Unread messages: {count}</p></body></html>"


def test_first_check_sets_baseline_without_alert(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_list_html(
                    2,
                    ("/lv/messages/1", "Anna", "See you tonight"),
                    ("/lv/messages/2", "Bruno", "Running 10 minutes late"),
                ),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    2,
                    ("/en/chat?conversation=1", 1, "Anna", "See you tonight"),
                    ("/en/chat?conversation=2", 1, "Bruno", "Running 10 minutes late"),
                ),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()
    assert result == "baseline_set:2"
    assert state["last_unread"] == 2
    assert state["last_success_ts"] is not None
    assert state["fetch_duration_ms"] is not None
    assert state["parse_duration_ms"] is not None
    assert len(state["last_unread_message_fingerprints"]) == 2
    assert telegram.sent == []


def test_increase_triggers_alert(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": [
                GribuResponse(
                    status_code=200,
                    url="https://www.gribu.lv/lv/messages",
                    text=_message_list_html(
                        2,
                        ("/lv/messages/1", "Anna", "See you tonight"),
                        ("/lv/messages/2", "Bruno", "Running 10 minutes late"),
                    ),
                ),
                GribuResponse(
                    status_code=200,
                    url="https://www.gribu.lv/lv/messages",
                    text=_message_list_html(
                        5,
                        ("/lv/messages/2", "Bruno", "Running 10 minutes late"),
                        ("/lv/messages/3", "Cara", "Fresh hello"),
                        ("/lv/messages/4", "Dmitry", "Another unread note"),
                        ("/lv/messages/5", "Eva", "See you soon"),
                        ("/lv/messages/6", "Frank", "Overflow preview"),
                    ),
                ),
            ],
            "/cats": [
                GribuResponse(
                    status_code=200,
                    url="https://www.gribu.lv/cats",
                    text=_cats_contact_list_html(
                        2,
                        ("/en/chat?conversation=1", 1, "Anna", "See you tonight"),
                        ("/en/chat?conversation=2", 1, "Bruno", "Running 10 minutes late"),
                    ),
                ),
                GribuResponse(
                    status_code=200,
                    url="https://www.gribu.lv/cats",
                    text=_cats_contact_list_html(
                        5,
                        ("/en/chat?conversation=2", 1, "Bruno", "Running 10 minutes late"),
                        ("/en/chat?conversation=3", 1, "Cara", "Fresh hello"),
                        ("/en/chat?conversation=4", 1, "Dmitry", "Another unread note"),
                        ("/en/chat?conversation=5", 1, "Eva", "See you soon"),
                        ("/en/chat?conversation=6", 1, "Frank", "Overflow preview"),
                    ),
                ),
            ],
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    baseline = service.run_check(force=True)
    assert baseline == "baseline_set:2"
    store.patch({"enabled": True, "paused_reason": "none"})

    result = service.run_check(force=False)
    state = store.load()
    assert result == "notified:2->5"
    assert len(telegram.sent) == 1
    assert "Unread increased: 2 -> 5" in telegram.sent[0]["text"]
    assert "New messages:" in telegram.sent[0]["text"]
    assert "- Cara: Fresh hello" in telegram.sent[0]["text"]
    assert "- Dmitry: Another unread note" in telegram.sent[0]["text"]
    assert "- Eva: See you soon" in telegram.sent[0]["text"]
    assert "Bruno: Running 10 minutes late" not in telegram.sent[0]["text"]
    assert "Open:" not in telegram.sent[0]["text"]
    assert "https://www.gribu.lv/lv/messages" not in telegram.sent[0]["text"]
    assert telegram.sent[0]["link_preview_enabled"] is False
    assert state["last_preview_source"] == "contact_selector"
    assert state["last_preview_item_count"] == 5
    assert state["last_notification_open_url"] is None
    assert state["last_notification_had_link_preview_requested"] is False


def test_increase_falls_back_to_count_only_when_no_previews_present(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": True, "paused_reason": "none", "last_unread": 2})
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(5),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_menu_html(5),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)

    assert result == "notified:2->5"
    assert len(telegram.sent) == 1
    assert "Unread increased: 2 -> 5" in telegram.sent[0]["text"]
    assert "New messages:" not in telegram.sent[0]["text"]
    assert "Open:" not in telegram.sent[0]["text"]
    assert telegram.sent[0]["link_preview_enabled"] is False


def test_preview_sync_uses_cats_contact_list_for_manual_check_text(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/cats",
        preview_url_candidates=("/cats",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(2),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    2,
                    ("/en/chat?conversation=1", 1, "Cara", "Fresh hello"),
                    ("/en/chat?conversation=2", 1, "Dmitry", "Another unread note"),
                ),
            ),
        },
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.command_checknow()
    state = store.load()

    assert "Manual check result: baseline_set:2" in result
    assert "Preview route: /cats" in result
    assert "Preview status: ok" in result
    assert "Last unread message: Cara: Fresh hello" in result
    assert state["resolved_preview_url"] == "/cats"
    assert state["last_preview_source"] == "contact_selector"
    assert state["last_preview_item_count"] == 2
    assert state["last_latest_unread_preview_text"] == "Cara: Fresh hello"
    assert state["last_unread_previews"][0]["text"] == "Cara: Fresh hello"
    assert gribu.calls == ["/lv/messages", "/cats"]
    assert gribu.api_calls == []


def test_preview_no_previews_keeps_last_good_preview_state(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/cats",
        preview_url_candidates=("/cats",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    store.patch(
        {
            "enabled": True,
            "paused_reason": "none",
            "last_unread": 2,
            "last_unread_previews": [
                {"fingerprint": "saved-1", "text": "Saved preview text", "href": "/cats"},
            ],
            "last_latest_unread_preview_text": "Saved preview text",
            "last_unread_message_fingerprints": ["saved-1"],
        }
    )
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(2),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_menu_html(2),
            ),
        },
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()

    assert result == "no_change:2->2"
    assert state["last_unread"] == 2
    assert state["last_preview_route_result"] == "no_previews"
    assert state["last_preview_error_message"] == "No unread contact previews found on /cats"
    assert state["last_preview_source"] == "none"
    assert state["last_preview_item_count"] == 0
    assert state["last_latest_unread_preview_text"] == "Saved preview text"
    assert state["last_unread_previews"][0]["text"] == "Saved preview text"
    assert gribu.api_calls == []
    assert telegram.sent == []


def test_preview_count_mismatch_suppresses_alert_preview_lines_but_keeps_manual_preview(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/cats",
        preview_url_candidates=("/cats",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    store.patch({"enabled": True, "paused_reason": "none", "last_unread": 1})
    gribu = FakeGribuClient(
        by_url={
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    4,
                    ("/en/chat?conversation=9", 1, "Laila", "Mismatch preview text"),
                ),
            ),
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(3),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()

    assert result == "notified:1->3"
    assert len(telegram.sent) == 1
    assert "New messages:" not in telegram.sent[0]["text"]
    assert state["last_preview_route_result"] == "count_mismatch"
    assert state["last_preview_source"] == "contact_selector"
    assert state["last_preview_item_count"] == 1
    assert state["last_latest_unread_preview_text"] == "Laila: Mismatch preview text"
    assert gribu.api_calls == []


def test_manual_check_reports_explicit_unavailable_reason_when_preview_missing(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/cats",
        preview_url_candidates=("/cats",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(2),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_menu_html(2),
            ),
        },
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.command_checknow()

    assert "Preview status: no_previews" in result
    assert "Preview route: /cats" in result
    assert (
        "Last unread message: unavailable (no unread contact previews found on /cats)"
        in result
    )
    assert gribu.api_calls == []


def test_preview_sync_falls_back_to_chat_api_when_selector_rows_missing(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/en/chat",
        preview_url_candidates=("/en/chat",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(2),
            ),
            "/en/chat": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/en/chat",
                text=_chat_shell_html(2, locale="en"),
            ),
        },
        api_by_url={
            "/api/en/conversations/1": _chat_api_conversations_response(
                ("conv-1", "Cara", "Fresh hello"),
                ("conv-2", "Dmitry", "Another unread note"),
            ),
        },
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.command_checknow()
    state = store.load()

    assert "Manual check result: baseline_set:2" in result
    assert "Preview route: /en/chat" in result
    assert "Preview status: ok" in result
    assert "Last unread message: Cara: Fresh hello" in result
    assert state["last_preview_source"] == "chat_api"
    assert state["last_preview_item_count"] == 2
    assert state["last_unread_previews"][0]["text"] == "Cara: Fresh hello"
    assert gribu.api_calls == ["/api/en/conversations/1"]


def test_preview_api_failure_sends_count_only_alert_and_keeps_last_good_preview_state(tmp_path: Path):
    config = _make_config(
        tmp_path,
        check_url="/lv/messages",
        check_url_candidates=("/lv/messages",),
        preview_url="/en/chat",
        preview_url_candidates=("/en/chat",),
    )
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url, config.gribu_preview_url)
    _prime_preview_route(store, config.gribu_preview_url)
    store.patch(
        {
            "enabled": True,
            "paused_reason": "none",
            "last_unread": 1,
            "last_unread_previews": [
                {"fingerprint": "saved-1", "text": "Saved preview text", "href": "/cats"},
            ],
            "last_latest_unread_preview_text": "Saved preview text",
            "last_unread_message_fingerprints": ["saved-1"],
        }
    )
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(2),
            ),
            "/en/chat": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/en/chat",
                text=_chat_shell_html(2, locale="en"),
            ),
        },
        api_errors_by_url={
            "/api/en/conversations/1": "upstream failed",
        },
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()

    assert result == "notified:1->2"
    assert len(telegram.sent) == 1
    assert "Unread increased: 1 -> 2" in telegram.sent[0]["text"]
    assert "New messages:" not in telegram.sent[0]["text"]
    assert "Open:" not in telegram.sent[0]["text"]
    assert "https://www.gribu.lv/en/chat" not in telegram.sent[0]["text"]
    assert telegram.sent[0]["link_preview_enabled"] is False
    assert state["last_preview_route_result"] == "request_error"
    assert state["last_preview_error_message"] == "Chat preview API request failed: upstream failed"
    assert state["last_preview_source"] == "none"
    assert state["last_preview_item_count"] == 0
    assert state["last_latest_unread_preview_text"] == "Saved preview text"
    assert state["last_unread_previews"][0]["text"] == "Saved preview text"
    assert state["last_notification_open_url"] is None
    assert state["last_notification_had_link_preview_requested"] is False
    assert gribu.api_calls == ["/api/en/conversations/1"]


def test_decrease_does_not_trigger_alert(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch(
        {
            "enabled": True,
            "paused_reason": "none",
            "last_unread": 5,
            "last_unread_message_fingerprints": ["old-a", "old-b"],
        }
    )
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_list_html(1, ("/lv/messages/9", "Ieva", "Only one left unread")),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    1,
                    ("/en/chat?conversation=9", 1, "Ieva", "Only one left unread"),
                ),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    assert result == "no_change:5->1"
    assert telegram.sent == []
    state = store.load()
    assert state["last_unread"] == 1
    assert len(state["last_unread_message_fingerprints"]) == 1


def test_no_change_refreshes_preview_fingerprints_without_alert(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch(
        {
            "enabled": True,
            "paused_reason": "none",
            "last_unread": 2,
            "last_unread_message_fingerprints": ["stale-preview"],
        }
    )
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_list_html(
                    2,
                    ("/lv/messages/1", "Anna", "Still unread"),
                    ("/lv/messages/2", "Bruno", "Also still unread"),
                ),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    2,
                    ("/en/chat?conversation=1", 1, "Anna", "Still unread"),
                    ("/en/chat?conversation=2", 1, "Bruno", "Also still unread"),
                ),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)

    assert result == "no_change:2->2"
    assert telegram.sent == []
    assert len(store.load()["last_unread_message_fingerprints"]) == 2


def test_increase_limits_preview_lines_and_reports_overflow(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": True, "paused_reason": "none", "last_unread": 1})
    long_preview = "A" * 240
    gribu = FakeGribuClient(
        by_url={
            "/lv/messages": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_message_html(7),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_cats_contact_list_html(
                    7,
                    ("/en/chat?conversation=1", 1, "One", long_preview),
                    ("/en/chat?conversation=2", 1, "Two", "Short preview 2"),
                    ("/en/chat?conversation=3", 1, "Three", "Short preview 3"),
                    ("/en/chat?conversation=4", 1, "Four", "Short preview 4"),
                    ("/en/chat?conversation=5", 1, "Five", "Short preview 5"),
                    ("/en/chat?conversation=6", 1, "Six", "Short preview 6"),
                    ("/en/chat?conversation=7", 1, "Seven", "Short preview 7"),
                ),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    text = telegram.sent[0]["text"]

    assert result == "notified:1->7"
    assert text.count("\n- ") == 5
    assert "...and 1 more" in text
    assert "One: " in text
    assert "A" * 205 not in text


def test_session_expiry_triggers_auto_reauth_and_recovers(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": True, "paused_reason": "none"})
    login_like_html = '<form data-login-form><input type="password" name="login"></form>'
    gribu = FakeGribuClient(
        responses=[
            GribuResponse(status_code=200, url="https://www.gribu.lv/login", text=login_like_html),
            GribuResponse(status_code=200, url="https://www.gribu.lv/lv/messages", text=_message_html(3)),
        ]
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator(outcomes=[None])
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()
    assert result == "baseline_set:3"
    assert state["enabled"] is True
    assert state["paused_reason"] == "none"
    assert auth.calls == [("demo@example.com", "secret")]
    assert telegram.sent == []


def test_session_expiry_alerts_and_pauses_when_reauth_fails(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": True, "paused_reason": "none"})
    login_like_html = '<form data-login-form><input type="password" name="login"></form>'
    gribu = FakeGribuClient(
        responses=[
            GribuResponse(status_code=200, url="https://www.gribu.lv/login", text=login_like_html),
        ]
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator(outcomes=[GribuAuthError("bad credentials")])
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()
    assert result == "session_expired"
    assert state["enabled"] is False
    assert state["paused_reason"] == "session_expired"
    assert len(telegram.sent) == 1
    assert "session appears expired" in telegram.sent[0]["text"]


def test_command_reauth_resumes_paused_checks(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": False, "paused_reason": "session_expired"})
    gribu = FakeGribuClient([])
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator(outcomes=[None])
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.command_reauth()
    state = store.load()
    assert result == "Reauth successful. Checks resumed."
    assert state["enabled"] is True
    assert state["paused_reason"] == "none"


def test_low_confidence_baseline_is_rejected(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    gribu = FakeGribuClient(
        responses=[
            GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_keyword_message_html(11),
            ),
        ]
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()
    assert result == "low_confidence_baseline_rejected"
    assert state["last_unread"] is None
    assert state["low_confidence_streak"] == 1
    assert state["last_parse_source"] == "keyword-near-match"
    assert state["last_parse_confidence"] < config.parse_min_confidence_baseline


def test_low_confidence_update_is_rejected(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    _prime_route(store, config.gribu_check_url)
    store.patch({"enabled": True, "paused_reason": "none", "last_unread": 2})
    gribu = FakeGribuClient(
        responses=[
            GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/lv/messages",
                text=_keyword_message_html(100),
            ),
        ]
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=False)
    state = store.load()
    assert result == "low_confidence_update_rejected"
    assert state["last_unread"] == 2
    assert state["low_confidence_streak"] == 1
    assert state["last_parse_source"] == "keyword-near-match"
    assert state["last_parse_confidence"] < config.parse_min_confidence_update


def test_route_resolver_selects_best_confident_candidate(tmp_path: Path):
    candidates = ("/fallback", "/json", "/strict")
    config = _make_config(
        tmp_path,
        check_url="/fallback",
        check_url_candidates=candidates,
        preview_url="/strict",
        preview_url_candidates=("/strict",),
        parse_min_confidence_route_selection=0.7,
    )
    store = StateStore(config.state_file)
    gribu = FakeGribuClient(
        by_url={
            "/fallback": GribuResponse(status_code=404, url="https://www.gribu.lv/fallback", text="Not found"),
            "/json": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/json",
                text='<script>window.boot={"newMessagesCount":5};</script>',
            ),
            "/strict": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/strict",
                text=_message_html(7),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()

    assert result == "baseline_set:7"
    assert state["resolved_check_url"] == "/strict"
    assert state["route_discovery_last_result"].startswith("selected:/strict:")
    assert len(state["route_discovery_last_candidates"]) == len(candidates)
    assert gribu.calls[:3] == list(candidates)
    assert gribu.calls[3] == "/strict"


def test_route_resolver_selects_cats_chat_badge_candidate(tmp_path: Path):
    candidates = ("/zinas", "/cats", "/messages")
    config = _make_config(
        tmp_path,
        check_url="/zinas",
        check_url_candidates=candidates,
        preview_url="/cats",
        preview_url_candidates=("/cats",),
        parse_min_confidence_route_selection=0.7,
    )
    store = StateStore(config.state_file)
    gribu = FakeGribuClient(
        by_url={
            "/zinas": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/zinas",
                text=_keyword_message_html(5),
            ),
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_menu_html(4),
            ),
            "/messages": GribuResponse(status_code=404, url="https://www.gribu.lv/messages", text="Not found"),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()

    assert result == "baseline_set:4"
    assert state["resolved_check_url"] == "/cats"
    assert state["route_discovery_last_result"].startswith("selected:/cats:")
    assert state["last_parse_source"] == "chat-badge:menu-tab"
    assert state["last_parse_confidence"] >= 0.9
    assert "error: route_resolution_failed" not in str(state.get("last_check_result"))


def test_route_resolver_prefers_primary_route_when_chat_badge_confidence_ties(tmp_path: Path):
    candidates = ("/cats", "/zinas")
    config = _make_config(
        tmp_path,
        check_url="/cats",
        check_url_candidates=candidates,
        preview_url="/cats",
        preview_url_candidates=("/cats",),
        parse_min_confidence_route_selection=0.7,
    )
    store = StateStore(config.state_file)
    gribu = FakeGribuClient(
        by_url={
            "/cats": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/cats",
                text=_chat_badge_absent_html(),
            ),
            "/zinas": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/zinas",
                text=_chat_badge_absent_html(),
            ),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()

    assert result == "baseline_set:0"
    assert state["resolved_check_url"] == "/cats"
    assert state["route_discovery_last_result"].startswith("selected:/cats:")


def test_route_resolver_failure_returns_error_without_mutating_baseline(tmp_path: Path):
    candidates = ("/low", "/missing")
    config = _make_config(
        tmp_path,
        check_url="/low",
        check_url_candidates=candidates,
        parse_min_confidence_route_selection=0.7,
    )
    store = StateStore(config.state_file)
    store.patch({"last_unread": 4})
    gribu = FakeGribuClient(
        by_url={
            "/low": GribuResponse(
                status_code=200,
                url="https://www.gribu.lv/low",
                text=_keyword_message_html(9),
            ),
            "/missing": GribuResponse(status_code=404, url="https://www.gribu.lv/missing", text="not found"),
        }
    )
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    result = service.run_check(force=True)
    state = store.load()

    assert result == "error: route_resolution_failed"
    assert state["last_unread"] == 4
    assert state["resolved_check_url"] is None
    assert state["route_discovery_last_result"] == "no_candidate_above_threshold"
    assert len(state["route_discovery_last_candidates"]) == len(candidates)


def test_status_and_debug_include_runtime_context_warning(tmp_path: Path):
    config = _make_config(tmp_path)
    store = StateStore(config.state_file)
    store.patch(
        {
            "enabled": True,
            "paused_reason": "none",
            "runtime_selinux_context": "u:r:magisk:s0",
            "runtime_context_warning": "Daemon is running under magisk/root context.",
        }
    )
    gribu = FakeGribuClient([])
    telegram = FakeTelegramClient()
    auth = FakeGribuAuthenticator()
    service = NotifierService(config, store, gribu, auth, telegram)

    status = service.command_status()
    debug = service.command_debug()

    assert "runtime_context" in status
    assert "run under orchestrator (component=site_notifier)" in status
    assert "runtime_selinux_context: u:r:magisk:s0" in debug
    assert "runtime_context_warning: Daemon is running under magisk/root context." in debug
