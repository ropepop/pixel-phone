from pathlib import Path

import pytest

import unread_parser
from unread_parser import UnreadParseError, extract_cats_unread_previews, parse_unread_count


def _message_list_html(*rows: tuple[str, str, str], count: int = 0) -> str:
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


def _cats_contact_list_html(*rows: tuple[str, int, str, str], total_unread: int = 0) -> str:
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
        "<header>"
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link'>"
        "<span class='header__bottom-row-name_optional'>Chat</span>"
        "<span class='header__counter-inner'>"
        "<span class='online-counter online-counter_header'>"
        f"<sup class='online-counter__count'>{total_unread}</sup>"
        "</span>"
        "</span>"
        "</a>"
        "</li>"
        "<nav>"
        "<a class='header__bottom-row-link' href='/cats'>Chat</a>"
        "<a class='header__bottom-row-link' href='/en/chat'>Forum</a>"
        "</nav>"
        "</header>"
        "<ul class='chat-contact-list'>"
        f"{items}"
        "</ul>"
        "</body></html>"
    )


FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"


def test_parse_chat_badge_from_desktop_menu_tab():
    html = (
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link'>"
        "<span class='header__counter-inner'>"
        "<span class='online-counter online-counter_header'>"
        "<sup class='online-counter__count'>3</sup>"
        "</span>"
        "</span>"
        "</a>"
        "</li>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 3
    assert result.source == "chat-badge:menu-tab"
    assert result.confidence >= 0.95


def test_parse_chat_badge_from_mobile_menu_button():
    html = (
        "<a class='header__mobile-chat-button button button_wide'>"
        "<span class='button__title'>Čats</span>"
        "<span class='header__mobile-chat-cnt online-counter online-counter_mini'>"
        "<span class='online-counter__count'>7</span>"
        "</span>"
        "</a>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 7
    assert result.source == "chat-badge:menu-tab"
    assert result.confidence >= 0.95


def test_parse_chat_badge_uses_attribute_when_text_is_empty():
    html = (
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats'>"
        "<span class='header__counter-inner'>"
        "<span class='online-counter'>"
        "<sup class='online-counter__count' data-online-count='11'></sup>"
        "</span>"
        "</span>"
        "</a>"
        "</li>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 11
    assert result.source == "chat-badge:menu-tab"
    assert result.confidence >= 0.95


def test_parse_chat_badge_absent_means_zero():
    html = (
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link _active'>"
        "<span class='header__bottom-row-name_optional'>Čats</span>"
        "</a>"
        "</li>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 0
    assert result.source == "chat-badge:menu-tab-absent"
    assert result.confidence >= 0.9


def test_parse_chat_badge_ignores_unrelated_online_counter_outside_chat_tab():
    html = (
        "<html><body>"
        "<span class='online-counter__count'>654</span>"
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats' class='header__bottom-row-link _active'>"
        "<span class='header__bottom-row-name_optional'>Čats</span>"
        "</a>"
        "</li>"
        "</body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 0
    assert result.source == "chat-badge:menu-tab-absent"


def test_parse_from_selector_data_count():
    html = '<div data-header-notification-count data-count="7"></div>'
    result = parse_unread_count(html)
    assert result.unread_count == 7
    assert result.confidence > 0.9
    assert result.previews == ()


def test_parse_collects_visible_message_previews():
    html = _message_list_html(
        ("/lv/messages/1", "Anna", "See you tonight"),
        ("/lv/messages/2", "Bruno", "Running 10 minutes late"),
        count=2,
    )

    result = parse_unread_count(html)

    assert result.unread_count == 2
    assert [preview.text for preview in result.previews] == [
        "Anna: See you tonight",
        "Bruno: Running 10 minutes late",
    ]
    assert [preview.href for preview in result.previews] == [
        "/lv/messages/1",
        "/lv/messages/2",
    ]
    assert len({preview.fingerprint for preview in result.previews}) == 2


def test_parse_preview_extraction_ignores_nav_chat_link():
    html = (
        "<html><body>"
        "<nav>"
        "<a class='nav-chat-link' href='/lv/chat'>Chat</a>"
        "</nav>"
        f"{_message_list_html(( '/lv/messages/10', 'Laila', 'Actual preview text here' ), count=1)}"
        "</body></html>"
    )

    result = parse_unread_count(html)

    assert result.unread_count == 1
    assert [preview.text for preview in result.previews] == ["Laila: Actual preview text here"]


def test_parse_preview_extraction_failure_does_not_break_count(monkeypatch):
    def _boom(_html: str):
        raise RuntimeError("preview failure")

    monkeypatch.setattr(unread_parser, "_extract_unread_previews", _boom)

    result = parse_unread_count('<div data-header-notification-count data-count="7"></div>')

    assert result.unread_count == 7
    assert result.previews == ()


def test_parse_authenticated_chat_shell_fixture_does_not_report_nav_as_preview():
    html = (FIXTURES_DIR / "gribu_en_chat_authenticated.html").read_text(encoding="utf-8")

    result = parse_unread_count(html)

    assert result.source.startswith("chat-badge:")
    assert result.previews == ()


def test_extract_cats_unread_previews_reads_only_unread_contact_rows():
    html = _cats_contact_list_html(
        ("/en/chat?conversation=1", 1, "Cara", "Fresh hello"),
        ("/en/chat?conversation=2", 0, "Dmitry", "Already read"),
        ("/en/chat?conversation=3", 2, "Eva", "Meet me later"),
        total_unread=3,
    )

    previews = extract_cats_unread_previews(html)

    assert [preview.text for preview in previews] == [
        "Cara: Fresh hello",
        "Eva: Meet me later",
    ]
    assert [preview.href for preview in previews] == [
        "/en/chat?conversation=1",
        "/en/chat?conversation=3",
    ]


def test_extract_cats_unread_previews_ignores_header_and_nav_noise():
    html = _cats_contact_list_html(
        ("/en/chat?conversation=10", 1, "Laila", "Actual unread snippet"),
        total_unread=1,
    )

    previews = extract_cats_unread_previews(html)

    assert [preview.text for preview in previews] == ["Laila: Actual unread snippet"]


def test_extract_cats_unread_previews_uses_contact_row_title_and_line_below_it():
    html = (
        "<html><body>"
        "<div class='chat-selector'>"
        "<div class='chat-selector-row shoutbox' data-conversation-id='shoutbox'>"
        "<a href='/cats/shoutbox'>"
        "<div class='chat-selector-row__title'>ShoutBox</div>"
        "<div class='chat-selector-row__status'>16 online</div>"
        "<div class='chat-selector-row__meta'>Atlasiet</div>"
        "</a>"
        "</div>"
        "<div class='chat-selector-row conversation' data-conversation-id='1'>"
        "<a href='/en/chat?conversation=1'>"
        "<div class='chat-selector-row__name'>LookHmen</div>"
        "<div class='chat-selector-row__subtitle'>Жалко далеко</div>"
        "<div class='chat-selector-row__status'>16 pirms minūtēm</div>"
        "<div class='chat-selector-row__badge'><span class='online-counter__count'>1</span></div>"
        "</a>"
        "</div>"
        "<div class='chat-selector-row conversation' data-conversation-id='2'>"
        "<a href='/en/chat?conversation=2'>"
        "<div class='chat-selector-row__name'>Dima1707</div>"
        "<div class='chat-selector-row__subtitle'>Sex!</div>"
        "<div class='chat-selector-row__status'>5 pirms stundām</div>"
        "<div class='chat-selector-row__badge'><span class='online-counter__count'>1</span></div>"
        "</a>"
        "</div>"
        "</div>"
        "</body></html>"
    )

    previews = extract_cats_unread_previews(html)

    assert [preview.text for preview in previews] == [
        "LookHmen: Жалко далеко",
        "Dima1707: Sex!",
    ]
    assert [preview.href for preview in previews] == [
        "/en/chat?conversation=1",
        "/en/chat?conversation=2",
    ]


def test_parse_from_keyword_line():
    html = "<html><body><p>Unread messages: 12</p></body></html>"
    result = parse_unread_count(html)
    assert result.unread_count == 12
    assert result.source == "keyword-near-match"
    assert result.confidence < 0.5


def test_parse_from_json_pattern():
    html = '<script>window.boot={"newMessagesCount":3};</script>'
    result = parse_unread_count(html)
    assert result.unread_count == 3
    assert result.source.startswith("json:")
    assert result.confidence > 0.9


def test_parse_prefers_high_confidence_signal_over_keyword_fallback():
    html = (
        "<html><body>"
        "<p>Unread messages: 88</p>"
        '<script>window.boot={"newMessagesCount":3};</script>'
        "</body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 3
    assert result.source.startswith("json:")


def test_parse_prefers_chat_badge_over_other_high_confidence_signals():
    html = (
        "<html><body>"
        "<li class='header__top-left-group-item header__top-left-group-item_chat'>"
        "<a href='/cats'><sup class='online-counter__count'>6</sup></a>"
        "</li>"
        '<div data-header-notification-count data-count="14"></div>'
        '<script>window.boot={"newMessagesCount":9};</script>'
        "</body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 6
    assert result.source == "chat-badge:menu-tab"
    assert result.confidence >= 0.95


def test_parse_ignores_noisy_small_numbers_when_strict_counter_exists():
    html = (
        "<html><body>"
        "<p>Unread messages: 1</p>"
        "<p>Online: 2</p>"
        '<div data-header-notification-count data-count="31"></div>'
        "</body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 31
    assert result.source == "[data-header-notification-count]:data-count"


def test_parse_keyword_fallback_ignores_script_and_style_noise():
    html = (
        "<html><head>"
        "<script>var unread='Unread messages: 999';</script>"
        "<style>.banner::after { content: 'messages 888'; }</style>"
        "</head><body><p>Unread messages: 12</p></body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 12
    assert result.source == "keyword-near-match"


def test_parse_large_html_keyword_fallback_stays_correct():
    repeated = "<div><span>noise</span><span>123</span></div>" * 500
    html = (
        "<html><body>"
        f"{repeated}"
        "<p>Unread messages: 42</p>"
        f"{repeated}"
        "</body></html>"
    )
    result = parse_unread_count(html)
    assert result.unread_count == 42
    assert result.source == "keyword-near-match"


def test_parse_raises_for_login_form_page():
    html = (
        "<html><body><form>"
        '<input type="text" name="login[email]">'
        '<input type="password" name="login[password]">'
        "</form></body></html>"
    )
    with pytest.raises(UnreadParseError):
        parse_unread_count(html)


def test_parse_raises_when_no_count():
    html = "<html><body><h1>No counters here</h1></body></html>"
    with pytest.raises(UnreadParseError):
        parse_unread_count(html)
