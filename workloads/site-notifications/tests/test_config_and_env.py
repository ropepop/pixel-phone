from __future__ import annotations

from pathlib import Path

import pytest

from config import ConfigError, load_config
from env_store import upsert_env_value


def _write_env(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _clear_relevant_env(monkeypatch) -> None:
    keys = [
        "TELEGRAM_BOT_TOKEN",
        "TELEGRAM_CHAT_ID",
        "GRIBU_BASE_URL",
        "GRIBU_CHECK_URL",
        "GRIBU_CHECK_URL_CANDIDATES",
        "GRIBU_PREVIEW_URL",
        "GRIBU_PREVIEW_URL_CANDIDATES",
        "GRIBU_LOGIN_ID",
        "GRIBU_LOGIN_PASSWORD",
        "GRIBU_LOGIN_PATH",
        "GRIBU_COOKIE_HEADER",
        "CHECK_INTERVAL_SEC",
        "CHECK_INTERVAL_FAST_SEC",
        "CHECK_INTERVAL_IDLE_SEC",
        "CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC",
        "STATE_FILE",
        "HTTP_TIMEOUT_SEC",
        "ERROR_ALERT_COOLDOWN_SEC",
        "TELEGRAM_API_BASE_URL",
        "TELEGRAM_NAV_BUTTONS_ENABLED",
        "DAEMON_LOCK_FILE",
        "WATCHDOG_CHECK_SEC",
        "WATCHDOG_STALE_SEC",
        "SUPERVISOR_RESTART_BASE_SEC",
        "SUPERVISOR_RESTART_MAX_SEC",
        "PARSE_LOW_CONFIDENCE_DELTA_LIMIT",
        "ROUTE_DISCOVERY_TTL_SEC",
        "PREVIEW_ROUTE_DISCOVERY_TTL_SEC",
        "PARSE_MIN_CONFIDENCE_BASELINE",
        "PARSE_MIN_CONFIDENCE_UPDATE",
        "PARSE_MIN_CONFIDENCE_ROUTE_SELECTION",
    ]
    for key in keys:
        monkeypatch.delenv(key, raising=False)


def test_missing_login_id_raises_config_error(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_PASSWORD=secret",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "GRIBU_LOGIN_ID" in str(exc.value)


def test_cookie_header_is_optional(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
        ],
    )

    config = load_config(str(env_path))
    assert config.gribu_cookie_header == ""
    assert config.env_file_path == env_path.resolve()
    assert config.state_file == (tmp_path / "state" / "state.json").resolve()
    assert config.daemon_lock_file == (tmp_path / "state" / "daemon.lock").resolve()
    assert config.watchdog_check_sec == 10
    assert config.watchdog_stale_sec == 120
    assert config.supervisor_restart_base_sec == 2
    assert config.supervisor_restart_max_sec == 30
    assert config.parse_low_confidence_delta_limit == 20
    assert config.check_interval_fast_sec == 20
    assert config.check_interval_idle_sec == 60
    assert config.check_interval_error_backoff_max_sec == 180
    assert config.gribu_check_url == "/lv/messages"
    assert config.gribu_check_url_candidates == (
        "/lv/messages",
        "/cats",
        "/zinas",
        "/user-activities",
        "/messages",
    )
    assert config.gribu_preview_url == "/lv/messages"
    assert config.gribu_preview_url_candidates == (
        "/lv/messages",
        "/en/chat",
        "/messages",
        "/cats",
    )
    assert config.route_discovery_ttl_sec == 21600
    assert config.preview_route_discovery_ttl_sec == 21600
    assert config.parse_min_confidence_baseline == 0.8
    assert config.parse_min_confidence_update == 0.7
    assert config.parse_min_confidence_route_selection == 0.7
    assert config.telegram_nav_buttons_enabled is True


def test_absolute_paths_are_preserved(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    explicit_state = (tmp_path / "custom" / "state.json").resolve()
    explicit_lock = (tmp_path / "custom" / "daemon.lock").resolve()
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            f"STATE_FILE={explicit_state}",
            f"DAEMON_LOCK_FILE={explicit_lock}",
        ],
    )

    config = load_config(str(env_path))
    assert config.state_file == explicit_state
    assert config.daemon_lock_file == explicit_lock


def test_watchdog_stale_must_be_greater_than_watchdog_check(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "WATCHDOG_CHECK_SEC=30",
            "WATCHDOG_STALE_SEC=30",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "WATCHDOG_STALE_SEC" in str(exc.value)


def test_supervisor_restart_max_must_be_at_least_base(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "SUPERVISOR_RESTART_BASE_SEC=10",
            "SUPERVISOR_RESTART_MAX_SEC=5",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "SUPERVISOR_RESTART_MAX_SEC" in str(exc.value)


def test_error_backoff_max_must_be_at_least_fast_interval(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "CHECK_INTERVAL_FAST_SEC=45",
            "CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC=30",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC" in str(exc.value)


def test_nav_buttons_boolean_is_parsed(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "TELEGRAM_NAV_BUTTONS_ENABLED=false",
        ],
    )

    config = load_config(str(env_path))
    assert config.telegram_nav_buttons_enabled is False


def test_custom_check_url_candidates_are_parsed(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "GRIBU_CHECK_URL=/zinas",
            "GRIBU_CHECK_URL_CANDIDATES=GRIBU_CHECK_URL,/user-activities,messages,/zinas,/user-activities",
        ],
    )

    config = load_config(str(env_path))
    assert config.gribu_check_url == "/zinas"
    assert config.gribu_check_url_candidates == (
        "/zinas",
        "/user-activities",
        "/messages",
    )


def test_custom_preview_url_candidates_are_parsed(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "GRIBU_PREVIEW_URL=/en/chat",
            "GRIBU_PREVIEW_URL_CANDIDATES=GRIBU_PREVIEW_URL,/messages,en/chat,/cats,/messages",
        ],
    )

    config = load_config(str(env_path))
    assert config.gribu_preview_url == "/en/chat"
    assert config.gribu_preview_url_candidates == (
        "/en/chat",
        "/messages",
        "/cats",
    )


def test_route_discovery_ttl_must_be_integer(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "ROUTE_DISCOVERY_TTL_SEC=abc",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "ROUTE_DISCOVERY_TTL_SEC" in str(exc.value)


def test_preview_route_discovery_ttl_must_be_integer(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "PREVIEW_ROUTE_DISCOVERY_TTL_SEC=abc",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "PREVIEW_ROUTE_DISCOVERY_TTL_SEC" in str(exc.value)


def test_confidence_thresholds_must_be_between_zero_and_one(tmp_path: Path, monkeypatch):
    _clear_relevant_env(monkeypatch)
    env_path = tmp_path / ".env"
    _write_env(
        env_path,
        [
            "TELEGRAM_BOT_TOKEN=token",
            "TELEGRAM_CHAT_ID=42",
            "GRIBU_LOGIN_ID=demo@example.com",
            "GRIBU_LOGIN_PASSWORD=secret",
            "PARSE_MIN_CONFIDENCE_UPDATE=1.2",
        ],
    )

    with pytest.raises(ConfigError) as exc:
        load_config(str(env_path))
    assert "PARSE_MIN_CONFIDENCE_UPDATE" in str(exc.value)


def test_upsert_env_value_rewrites_cookie_line_only(tmp_path: Path):
    env_path = tmp_path / ".env"
    original = (
        "TELEGRAM_BOT_TOKEN=token\n"
        "GRIBU_COOKIE_HEADER=OLD=1; OLD2=2\n"
        "CHECK_INTERVAL_SEC=60\n"
    )
    env_path.write_text(original, encoding="utf-8")

    upsert_env_value(env_path, "GRIBU_COOKIE_HEADER", "DATED=abc; DATINGSES=def")
    updated = env_path.read_text(encoding="utf-8")

    assert "TELEGRAM_BOT_TOKEN=token\n" in updated
    assert "CHECK_INTERVAL_SEC=60\n" in updated
    assert "GRIBU_COOKIE_HEADER=DATED=abc; DATINGSES=def\n" in updated
    assert "GRIBU_COOKIE_HEADER=OLD=1; OLD2=2\n" not in updated
