from __future__ import annotations

import hashlib
import html as html_lib
import re
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Iterable


class UnreadParseError(Exception):
    pass


@dataclass(frozen=True)
class UnreadPreview:
    fingerprint: str
    text: str
    href: str | None


@dataclass(frozen=True)
class ParseResult:
    unread_count: int
    source: str
    confidence: float
    previews: tuple[UnreadPreview, ...] = ()


@dataclass(frozen=True)
class _Candidate:
    unread_count: int
    source: str
    confidence: float
    priority: int


@dataclass
class _Node:
    tag: str
    attrs: dict[str, str]
    children: list[_Node | str]
    parent: _Node | None = None


class _HtmlTreeBuilder(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node(tag="document", attrs={}, children=[])
        self._stack: list[_Node] = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = _Node(
            tag=tag.lower(),
            attrs={
                (key or "").lower(): (value or "")
                for key, value in attrs
                if (key or "").strip()
            },
            children=[],
            parent=self._stack[-1],
        )
        self._stack[-1].children.append(node)
        self._stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        node = _Node(
            tag=tag.lower(),
            attrs={
                (key or "").lower(): (value or "")
                for key, value in attrs
                if (key or "").strip()
            },
            children=[],
            parent=self._stack[-1],
        )
        self._stack[-1].children.append(node)

    def handle_endtag(self, tag: str) -> None:
        target = tag.lower()
        for idx in range(len(self._stack) - 1, 0, -1):
            if self._stack[idx].tag == target:
                del self._stack[idx:]
                return

    def handle_data(self, data: str) -> None:
        if data:
            self._stack[-1].children.append(data)


_STRICT_SELECTOR_ATTRS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("[data-header-notification-count]", ("data-count", "data-unread", "data-messages-count")),
    ("[data-unread-count]", ("data-unread", "data-count")),
    ("[data-messages-count]", ("data-messages-count", "data-count")),
    (".header__button-notification_new", ("data-count", "data-unread", "aria-label", "title")),
    (".messages-new", ("data-count", "data-unread", "aria-label", "title")),
    (".messages-count", ("data-count", "data-unread", "aria-label", "title")),
    (".chat-count", ("data-count", "data-unread", "aria-label", "title")),
)

_NEAR_KEYWORD_PATTERN = re.compile(
    r"(?:unread|message|messages|chat|mail|zi[nņ]a|zi[nņ]as|neizlas[a-z]*)"
    r"[^0-9]{0,30}(\d{1,4})",
    flags=re.IGNORECASE,
)

_JSON_COUNT_PATTERNS = (
    re.compile(r'"unread(?:_count|Count)?":\s*(\d{1,4})'),
    re.compile(r'"newMessagesCount":\s*(\d{1,4})'),
    re.compile(r'"messagesUnread":\s*(\d{1,4})'),
)

_LOGIN_EMAIL_PATTERN = re.compile(r"""name\s*=\s*["']login\[email\]["']""", flags=re.IGNORECASE)
_LOGIN_PASSWORD_PATTERN = re.compile(
    r"""name\s*=\s*["']login\[password\]["']""",
    flags=re.IGNORECASE,
)

_DIGIT_PATTERN = re.compile(r"\d{1,4}")
_SCRIPT_STYLE_TAG_PATTERN = re.compile(
    r"<(?:script|style)\b[^>]*>.*?</(?:script|style)>",
    flags=re.IGNORECASE | re.DOTALL,
)
_HTML_TAG_PATTERN = re.compile(r"<[^>]+>")
_WHITESPACE_PATTERN = re.compile(r"\s+")
_OPENING_TAG_PATTERN = re.compile(
    r"<(?P<tag>[a-zA-Z][a-zA-Z0-9:_-]*)\b(?P<attrs>[^>]*)>",
    flags=re.IGNORECASE,
)
_ATTR_TOKEN_PATTERN = re.compile(
    r"""([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""",
)
_CHAT_BADGE_CONTAINER_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"""<li\b[^>]*class\s*=\s*(?:"[^"]*header__top-left-group-item_chat[^"]*"|'[^']*header__top-left-group-item_chat[^']*')[^>]*>.*?</li>""",
        flags=re.IGNORECASE | re.DOTALL,
    ),
    re.compile(
        r"""<a\b[^>]*class\s*=\s*(?:"[^"]*header__mobile-chat-button[^"]*"|'[^']*header__mobile-chat-button[^']*')[^>]*>.*?</a>""",
        flags=re.IGNORECASE | re.DOTALL,
    ),
)
_CHAT_BADGE_TAG_PATTERN = re.compile(
    r"""<(?P<tag>span|sup)\b(?P<attrs>(?=[^>]*class\s*=\s*(?:"[^"]*online-counter__count[^"]*"|'[^']*online-counter__count[^']*'))[^>]*)>""",
    flags=re.IGNORECASE,
)

_PREVIEW_ROW_HINTS = (
    "message",
    "messages",
    "mail",
    "inbox",
    "chat",
    "dialog",
    "conversation",
    "thread",
    "correspondence",
    "pm",
)
_PREVIEW_TITLE_HINTS = (
    "title",
    "subject",
    "sender",
    "from",
    "user",
    "author",
    "name",
    "heading",
)
_PREVIEW_TEXT_HINTS = (
    "preview",
    "snippet",
    "excerpt",
    "body",
    "content",
    "last-message",
    "message-text",
    "message-preview",
)
_PREVIEW_ID_ATTRS = {
    "data-message-id",
    "data-chat-id",
    "data-dialog-id",
    "data-thread-id",
    "data-conversation-id",
}
_CATS_ROW_HINTS = (
    "contact",
    "conversation",
    "dialog",
    "thread",
    "chat-list-item",
    "chat-contact",
    "contact-list-item",
    "conversation-list-item",
    "dialog-list-item",
)
_CATS_TITLE_HINTS = _PREVIEW_TITLE_HINTS + (
    "nickname",
    "username",
    "contact-name",
    "dialog-name",
    "chat-name",
)
_CATS_TEXT_HINTS = _PREVIEW_TEXT_HINTS + (
    "last-message",
    "chat-preview",
    "contact-preview",
    "dialog-preview",
    "chat-snippet",
    "contact-snippet",
)
_CATS_UNREAD_HINTS = (
    "unread",
    "new-message",
    "new-messages",
    "message-count",
    "messages-count",
    "counter",
    "badge",
    "online-counter__count",
)
_MESSAGE_HREF_KEYWORDS = (
    "/message",
    "/messages",
    "/mail",
    "/chat",
    "/cats",
    "/dialog",
    "/dialogs",
    "/conversation",
    "/thread",
    "pm=",
)
_PREVIEW_CONTAINER_TAGS = {"a", "article", "div", "li", "section", "tr"}
_NOISE_PREVIEW_TEXTS = {
    "chat",
    "message",
    "messages",
    "inbox",
    "mail",
    "čats",
    "zinas",
    "ziņas",
}
_CATS_BLOCKLIST_TEXTS = {
    "shoutbox",
    "atlasiet",
    "visas",
    "personīgais",
    "grupas",
}
_TITLE_MAX_LEN = 80
_PREVIEW_MAX_SOURCE_LEN = 500
_SKIPPED_HREF_PREFIXES = ("#", "javascript:", "mailto:", "tel:")
_SHORT_CATS_PREVIEW_MIN_LEN = 2
_TIMESTAMP_LIKE_PATTERN = re.compile(
    r"""
    ^
    (?:
        \d{1,2}:\d{2}
        |
        \d+\s*(?:online|offline)
        |
        \d+\s*(?:pirms\s+)?(?:sek(?:und(?:ēm|es)?)?|min(?:ūt(?:ēm|es)?)?|stund(?:ām|as)?)
        |
        (?:pirms\s+)?\d+\s*(?:sek(?:und(?:ēm|es)?)?|min(?:ūt(?:ēm|es)?)?|stund(?:ām|as)?)
    )
    $
    """,
    flags=re.IGNORECASE | re.VERBOSE,
)
_STATUS_LIKE_PATTERN = re.compile(
    r"""^(?:[+*•·-]\s*)?(?:online|offline|aktīvs|aktīva|active|available)$""",
    flags=re.IGNORECASE,
)


def _to_ints(text: str) -> list[int]:
    return [int(match) for match in _DIGIT_PATTERN.findall(text)]


def _first_value(values: Iterable[int]) -> int | None:
    for value in values:
        if 0 <= value <= 9999:
            return value
    return None


def _looks_like_login_form(html: str) -> bool:
    return bool(_LOGIN_EMAIL_PATTERN.search(html) and _LOGIN_PASSWORD_PATTERN.search(html))


def _choose_best(candidates: list[_Candidate]) -> ParseResult | None:
    if not candidates:
        return None
    chosen = max(candidates, key=lambda item: (item.confidence, item.priority))
    return ParseResult(
        unread_count=chosen.unread_count,
        source=chosen.source,
        confidence=chosen.confidence,
    )


def _extract_json_candidate(raw_html: str) -> _Candidate | None:
    for pattern in _JSON_COUNT_PATTERNS:
        match = pattern.search(raw_html)
        if not match:
            continue
        return _Candidate(
            unread_count=int(match.group(1)),
            source=f"json:{pattern.pattern}",
            confidence=0.93,
            priority=95,
        )
    return None


def _parse_attrs(raw_attrs: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for match in _ATTR_TOKEN_PATTERN.finditer(raw_attrs):
        key = (match.group(1) or "").strip().lower()
        if not key:
            continue
        value = match.group(2) or match.group(3) or match.group(4) or ""
        parsed[key] = value
    return parsed


def _selector_matches(selector: str, attrs: dict[str, str]) -> bool:
    if selector.startswith("[") and selector.endswith("]"):
        required_attr = selector[1:-1].strip().lower()
        return required_attr in attrs
    if selector.startswith("."):
        class_name = selector[1:]
        class_tokens = attrs.get("class", "").split()
        return class_name in class_tokens
    return False


def _extract_chat_badge_value(raw_attrs: str, inner_html: str) -> int | None:
    attrs = _parse_attrs(raw_attrs)

    text_without_tags = _HTML_TAG_PATTERN.sub(" ", inner_html)
    parsed_value = _first_value(_to_ints(html_lib.unescape(text_without_tags)))
    if parsed_value is not None:
        return parsed_value

    for attr in ("data-online-count", "data-count", "aria-label", "title"):
        attr_value = attrs.get(attr)
        if not attr_value:
            continue
        parsed_value = _first_value(_to_ints(attr_value))
        if parsed_value is not None:
            return parsed_value
    return None


def _extract_menu_chat_badge_candidate(raw_html: str) -> _Candidate | None:
    saw_chat_tab_container = False
    for container_pattern in _CHAT_BADGE_CONTAINER_PATTERNS:
        for container_match in container_pattern.finditer(raw_html):
            saw_chat_tab_container = True
            container_html = container_match.group(0)
            for badge_match in _CHAT_BADGE_TAG_PATTERN.finditer(container_html):
                close_pattern = re.compile(
                    rf"</\s*{re.escape(badge_match.group('tag'))}\s*>",
                    flags=re.IGNORECASE,
                )
                inner_start = badge_match.end()
                close_match = close_pattern.search(container_html, inner_start)
                inner_html = (
                    container_html[inner_start:close_match.start()]
                    if close_match is not None
                    else ""
                )
                badge_value = _extract_chat_badge_value(
                    badge_match.group("attrs") or "",
                    inner_html,
                )
                if badge_value is None:
                    continue
                return _Candidate(
                    unread_count=badge_value,
                    source="chat-badge:menu-tab",
                    confidence=0.95,
                    priority=120,
                )

    if saw_chat_tab_container:
        return _Candidate(
            unread_count=0,
            source="chat-badge:menu-tab-absent",
            confidence=0.9,
            priority=110,
        )
    return None


def _opening_tag_text_nearby(raw_html: str, offset: int) -> str:
    snippet = raw_html[offset : offset + 120]
    direct_text = snippet.split("<", 1)[0]
    return _WHITESPACE_PATTERN.sub(" ", html_lib.unescape(direct_text)).strip()


def _collect_strict_candidates(raw_html: str) -> list[_Candidate]:
    candidates: list[_Candidate] = []
    for tag_match in _OPENING_TAG_PATTERN.finditer(raw_html):
        attrs = _parse_attrs(tag_match.group("attrs") or "")
        if not attrs:
            continue
        for selector, selector_attrs in _STRICT_SELECTOR_ATTRS:
            if not _selector_matches(selector, attrs):
                continue

            for attr in selector_attrs:
                attr_value = attrs.get(attr)
                if not attr_value:
                    continue
                value = _first_value(_to_ints(attr_value))
                if value is None:
                    continue
                candidates.append(
                    _Candidate(
                        unread_count=value,
                        source=f"{selector}:{attr}",
                        confidence=0.98,
                        priority=100,
                    )
                )

            text_value = _first_value(_to_ints(_opening_tag_text_nearby(raw_html, tag_match.end())))
            if text_value is None:
                continue
            candidates.append(
                _Candidate(
                    unread_count=text_value,
                    source=f"{selector}:text",
                    confidence=0.9,
                    priority=90,
                )
            )
    return candidates


def _strip_markup_for_keyword_search(raw_html: str) -> str:
    text = _SCRIPT_STYLE_TAG_PATTERN.sub(" ", raw_html)
    text = _HTML_TAG_PATTERN.sub(" ", text)
    text = html_lib.unescape(text)
    return _WHITESPACE_PATTERN.sub(" ", text)


def _normalize_text(text: str) -> str:
    normalized = html_lib.unescape(text or "").replace("\xa0", " ")
    normalized = _WHITESPACE_PATTERN.sub(" ", normalized)
    return normalized.strip(" \t\r\n-:|")


def _iter_nodes(node: _Node) -> Iterable[_Node]:
    for child in node.children:
        if isinstance(child, str):
            continue
        yield child
        yield from _iter_nodes(child)


def _node_text(node: _Node) -> str:
    parts: list[str] = []

    def _visit(current: _Node) -> None:
        for child in current.children:
            if isinstance(child, str):
                parts.append(child)
            else:
                _visit(child)

    _visit(node)
    return _normalize_text(" ".join(parts))


def _node_depth(node: _Node) -> int:
    depth = 0
    current = node.parent
    while current is not None:
        depth += 1
        current = current.parent
    return depth


def _node_attr_blob(node: _Node) -> str:
    tokens: list[str] = []
    for key, value in node.attrs.items():
        tokens.append(key.lower())
        if value:
            tokens.append(str(value).lower())
    return " ".join(tokens)


def _node_has_hint(node: _Node, hints: tuple[str, ...]) -> bool:
    blob = _node_attr_blob(node)
    return any(hint in blob for hint in hints)


def _node_has_preview_id(node: _Node) -> bool:
    return any(attr in node.attrs for attr in _PREVIEW_ID_ATTRS)


def _iter_descendant_anchors(node: _Node) -> Iterable[_Node]:
    for descendant in _iter_nodes(node):
        if descendant.tag == "a" and descendant.attrs.get("href"):
            yield descendant


def _is_message_href(href: str) -> bool:
    normalized = html_lib.unescape(href or "").strip().lower()
    if not normalized:
        return False
    return any(keyword in normalized for keyword in _MESSAGE_HREF_KEYWORDS)


def _message_hrefs(node: _Node) -> list[str]:
    hrefs: list[str] = []
    seen: set[str] = set()
    for anchor in _iter_descendant_anchors(node):
        href = (anchor.attrs.get("href") or "").strip()
        if not href or not _is_message_href(href) or href in seen:
            continue
        seen.add(href)
        hrefs.append(href)
    return hrefs


def _is_noise_preview_text(text: str) -> bool:
    normalized = _normalize_text(text)
    if not normalized:
        return True
    lowered = normalized.lower()
    if lowered in _NOISE_PREVIEW_TEXTS or lowered.isdigit():
        return True
    word_count = len([part for part in lowered.split(" ") if part])
    return len(normalized) < 8 or (word_count < 2 and len(normalized) < 12)


def _is_timestamp_like(text: str) -> bool:
    normalized = _normalize_text(text)
    if not normalized:
        return False
    lowered = normalized.lower()
    if _TIMESTAMP_LIKE_PATTERN.fullmatch(lowered):
        return True
    if "pirms" in lowered and any(char.isdigit() for char in lowered):
        return True
    return False


def _is_status_like(text: str) -> bool:
    normalized = _normalize_text(text)
    if not normalized:
        return False
    lowered = normalized.lower()
    if lowered in _CATS_BLOCKLIST_TEXTS:
        return True
    if _STATUS_LIKE_PATTERN.fullmatch(lowered):
        return True
    if ("online" in lowered or "offline" in lowered) and len(lowered.split()) <= 3:
        return True
    return False


def _is_noise_cats_preview_text(text: str) -> bool:
    normalized = _normalize_text(text)
    if not normalized:
        return True
    lowered = normalized.lower()
    if lowered in _NOISE_PREVIEW_TEXTS or lowered in _CATS_BLOCKLIST_TEXTS or lowered.isdigit():
        return True
    if _is_timestamp_like(normalized) or _is_status_like(normalized):
        return True
    if len(normalized) < _SHORT_CATS_PREVIEW_MIN_LEN:
        return True
    if not any(char.isalnum() for char in normalized):
        return True
    return False


def _is_noise_title_text(text: str) -> bool:
    normalized = _normalize_text(text)
    if not normalized:
        return True
    lowered = normalized.lower()
    return lowered in _NOISE_PREVIEW_TEXTS or normalized.isdigit() or len(normalized) > _TITLE_MAX_LEN


def _collect_hinted_titles(node: _Node) -> list[str]:
    return _collect_hinted_texts(node, _PREVIEW_TITLE_HINTS, _is_noise_title_text)


def _collect_hinted_previews(node: _Node) -> list[str]:
    return _collect_hinted_texts(node, _PREVIEW_TEXT_HINTS, _is_noise_preview_text)


def _collect_hinted_texts(
    node: _Node,
    hints: tuple[str, ...],
    noise_filter,
) -> list[str]:
    items: list[str] = []
    seen: set[str] = set()
    for descendant in _iter_nodes(node):
        if not _node_has_hint(descendant, hints):
            continue
        text = _normalize_text(_node_text(descendant))
        key = text.lower()
        if noise_filter(text) or key in seen:
            continue
        seen.add(key)
        items.append(text)
    return items


def _is_local_href(href: str) -> bool:
    normalized = html_lib.unescape(href or "").strip().lower()
    if not normalized:
        return False
    if normalized.startswith(_SKIPPED_HREF_PREFIXES):
        return False
    if normalized.startswith(("http://", "https://")):
        return "gribu.lv" in normalized
    return True


def _local_hrefs(node: _Node) -> list[str]:
    hrefs: list[str] = []
    seen: set[str] = set()
    for anchor in _iter_descendant_anchors(node):
        href = (anchor.attrs.get("href") or "").strip()
        if not href or not _is_local_href(href) or href in seen:
            continue
        seen.add(href)
        hrefs.append(href)
    return hrefs


def _extract_hinted_count_from_node(node: _Node, hints: tuple[str, ...]) -> int | None:
    if not _node_has_hint(node, hints):
        return None

    for attr in ("data-unread", "data-count", "data-online-count", "aria-label", "title"):
        attr_value = node.attrs.get(attr)
        if not attr_value:
            continue
        value = _first_value(_to_ints(attr_value))
        if value is not None:
            return value

    return _first_value(_to_ints(_node_text(node)))


def _collect_hinted_counts(node: _Node, hints: tuple[str, ...]) -> list[int]:
    counts: list[int] = []
    seen: set[int] = set()
    for descendant in _iter_nodes(node):
        value = _extract_hinted_count_from_node(descendant, hints)
        if value is None or value in seen:
            continue
        seen.add(value)
        counts.append(value)
    return counts


def _node_has_row_hint(node: _Node, hints: tuple[str, ...]) -> bool:
    if _node_has_hint(node, hints):
        return True
    return any(_node_has_hint(descendant, hints) for descendant in _iter_nodes(node))


def _node_has_preview_identity(node: _Node) -> bool:
    if _node_has_preview_id(node):
        return True
    return any(_node_has_preview_id(descendant) for descendant in _iter_nodes(node))


def _collect_cats_titles(node: _Node) -> list[str]:
    return _collect_hinted_texts(node, _CATS_TITLE_HINTS, _is_noise_title_text)


def _collect_cats_previews(node: _Node) -> list[str]:
    return _collect_hinted_texts(node, _CATS_TEXT_HINTS, _is_noise_cats_preview_text)


def _collect_ordered_text_fragments(node: _Node) -> list[str]:
    fragments: list[str] = []
    seen: set[str] = set()

    def _emit(text: str) -> None:
        normalized = _normalize_text(text)
        key = normalized.lower()
        if not normalized or key in seen:
            return
        seen.add(key)
        fragments.append(normalized)

    def _visit(current: _Node) -> None:
        direct_parts: list[str] = []
        for child in current.children:
            if isinstance(child, str):
                direct_parts.append(child)
                continue
            if direct_parts:
                _emit(" ".join(direct_parts))
                direct_parts = []
            _visit(child)
        if direct_parts:
            _emit(" ".join(direct_parts))

    _visit(node)
    return fragments


def _fallback_cats_title(node: _Node) -> str | None:
    for fragment in _collect_ordered_text_fragments(node):
        if _is_noise_title_text(fragment):
            continue
        if _is_timestamp_like(fragment) or _is_status_like(fragment):
            continue
        lowered = fragment.lower()
        if lowered in _CATS_BLOCKLIST_TEXTS:
            continue
        return fragment
    return None


def _fallback_cats_preview(node: _Node, title: str | None) -> str | None:
    normalized_title = _normalize_text(title or "").lower()
    for fragment in _collect_ordered_text_fragments(node):
        lowered = fragment.lower()
        if normalized_title and lowered == normalized_title:
            continue
        preview_body = _normalize_text(_strip_title_prefix(fragment, title))
        if not preview_body:
            continue
        if preview_body.lower() == normalized_title:
            continue
        if _is_noise_cats_preview_text(preview_body):
            continue
        return preview_body
    return None


def _cats_row_score(node: _Node) -> int:
    score = 0
    if _node_has_preview_identity(node):
        score += 4
    if _node_has_row_hint(node, _CATS_ROW_HINTS):
        score += 3
    if node.tag in {"li", "article", "tr"}:
        score += 2
    elif node.tag == "a":
        score += 1
    return score


def _build_cats_preview_from_node(node: _Node) -> UnreadPreview | None:
    if node.tag not in _PREVIEW_CONTAINER_TAGS:
        return None

    hrefs = _local_hrefs(node)
    if len(hrefs) > 1 and node.tag != "a":
        return None

    row_has_identity = _node_has_preview_identity(node)
    row_has_hint = _node_has_row_hint(node, _CATS_ROW_HINTS)
    if not row_has_identity and not row_has_hint:
        return None

    unread_counts = [value for value in _collect_hinted_counts(node, _CATS_UNREAD_HINTS) if value > 0]
    if not unread_counts:
        return None

    titles = _collect_cats_titles(node)
    preview_texts = _collect_cats_previews(node)
    if not titles:
        fallback_title = _fallback_cats_title(node)
        if fallback_title:
            titles = [fallback_title]
    if titles and not preview_texts:
        fallback_preview = _fallback_cats_preview(node, titles[0])
        if fallback_preview:
            preview_texts = [fallback_preview]
    if not titles or not preview_texts:
        return None

    title = titles[0]
    if title.lower() in _CATS_BLOCKLIST_TEXTS:
        return None
    preview_body = _strip_title_prefix(preview_texts[0], title)
    preview_body = _normalize_text(preview_body)
    if _is_noise_cats_preview_text(preview_body):
        return None

    href = hrefs[0] if hrefs else None
    if href is None and not row_has_identity:
        return None

    combined = f"{title}: {preview_body}"
    fingerprint_source = "\n".join(
        part
        for part in (
            (href or "").strip(),
            _normalize_text(title),
            _normalize_text(preview_body),
        )
        if part
    )[:_PREVIEW_MAX_SOURCE_LEN]
    if not fingerprint_source:
        return None

    fingerprint = hashlib.sha1(fingerprint_source.encode("utf-8")).hexdigest()[:16]
    return UnreadPreview(fingerprint=fingerprint, text=combined, href=href)


def _strip_title_prefix(text: str, title: str | None) -> str:
    normalized_text = _normalize_text(text)
    if not title:
        return normalized_text
    prefix = _normalize_text(title)
    if not prefix:
        return normalized_text
    pattern = re.compile(rf"^{re.escape(prefix)}(?:\s*[:\-|]\s*|\s+)?", flags=re.IGNORECASE)
    return _normalize_text(pattern.sub("", normalized_text, count=1))


def _build_preview_from_node(node: _Node) -> UnreadPreview | None:
    hrefs = _message_hrefs(node)
    href = hrefs[0] if hrefs else None
    titles = _collect_hinted_titles(node)
    title = titles[0] if titles else None
    preview_texts = _collect_hinted_previews(node)

    preview_body = preview_texts[0] if preview_texts else _strip_title_prefix(_node_text(node), title)
    preview_body = _normalize_text(preview_body)
    if title and preview_body.lower() == title.lower():
        preview_body = ""

    if preview_body and title and title.lower() not in preview_body.lower():
        combined = f"{title}: {preview_body}"
    else:
        combined = preview_body or title or ""
    combined = _normalize_text(combined)
    if _is_noise_preview_text(combined):
        return None

    fingerprint_parts = [
        (href or "").strip(),
        _normalize_text(title or ""),
        _normalize_text(preview_body or combined),
    ]
    fingerprint_source = "\n".join(part for part in fingerprint_parts if part)[:_PREVIEW_MAX_SOURCE_LEN]
    if not fingerprint_source:
        return None

    fingerprint = hashlib.sha1(fingerprint_source.encode("utf-8")).hexdigest()[:16]
    return UnreadPreview(fingerprint=fingerprint, text=combined, href=href)


def _preview_row_score(node: _Node) -> int | None:
    if node.tag not in _PREVIEW_CONTAINER_TAGS:
        return None

    text = _node_text(node)
    if _is_noise_preview_text(text):
        return None

    message_hrefs = _message_hrefs(node)
    if len(message_hrefs) > 1 and node.tag != "a":
        return None

    has_preview_body_signal = _node_has_preview_id(node) or _node_has_hint(node, _PREVIEW_TEXT_HINTS)
    if not has_preview_body_signal:
        has_preview_body_signal = any(
            _node_has_hint(descendant, _PREVIEW_TEXT_HINTS)
            for descendant in _iter_nodes(node)
        )
    if not has_preview_body_signal:
        return None

    score = 0
    if _node_has_preview_id(node):
        score += 4
    if _node_has_hint(node, _PREVIEW_ROW_HINTS):
        score += 3
    if message_hrefs:
        score += 3
    if has_preview_body_signal:
        score += 2
    if node.tag in {"a", "article", "li", "tr"}:
        score += 1
    if len(text) > 240:
        score -= 1
    if len(text) > 500:
        score -= 2

    return score if score >= 5 else None


def _extract_unread_previews(raw_html: str) -> tuple[UnreadPreview, ...]:
    builder = _HtmlTreeBuilder()
    builder.feed(raw_html)
    candidates: list[tuple[int, int, int, UnreadPreview]] = []

    for order, node in enumerate(_iter_nodes(builder.root)):
        score = _preview_row_score(node)
        if score is None:
            continue
        preview = _build_preview_from_node(node)
        if preview is None:
            continue
        candidates.append((order, -_node_depth(node), -score, preview))

    candidates.sort(key=lambda item: (item[0], item[1], item[2]))

    previews: list[UnreadPreview] = []
    seen: set[str] = set()
    seen_texts: set[str] = set()
    for _, _, _, preview in candidates:
        normalized_text = preview.text.lower()
        if preview.fingerprint in seen or normalized_text in seen_texts:
            continue
        seen.add(preview.fingerprint)
        seen_texts.add(normalized_text)
        previews.append(preview)
    return tuple(previews)


def extract_cats_unread_previews(raw_html: str) -> tuple[UnreadPreview, ...]:
    builder = _HtmlTreeBuilder()
    builder.feed(raw_html)
    candidates: list[tuple[int, int, int, UnreadPreview]] = []

    for order, node in enumerate(_iter_nodes(builder.root)):
        preview = _build_cats_preview_from_node(node)
        if preview is None:
            continue
        candidates.append((order, -_node_depth(node), -_cats_row_score(node), preview))

    candidates.sort(key=lambda item: (item[0], item[1], item[2]))

    previews: list[UnreadPreview] = []
    seen: set[str] = set()
    seen_texts: set[str] = set()
    for _, _, _, preview in candidates:
        normalized_text = preview.text.lower()
        if preview.fingerprint in seen or normalized_text in seen_texts:
            continue
        seen.add(preview.fingerprint)
        seen_texts.add(normalized_text)
        previews.append(preview)
    return tuple(previews)


def parse_unread_count(html: str) -> ParseResult:
    if _looks_like_login_form(html):
        raise UnreadParseError("Login form detected instead of messages page")

    previews: tuple[UnreadPreview, ...] = ()
    try:
        previews = _extract_unread_previews(html)
    except Exception:
        previews = ()

    menu_chat_badge_candidate = _extract_menu_chat_badge_candidate(html)
    if menu_chat_badge_candidate is not None:
        return ParseResult(
            unread_count=menu_chat_badge_candidate.unread_count,
            source=menu_chat_badge_candidate.source,
            confidence=menu_chat_badge_candidate.confidence,
            previews=previews,
        )

    json_candidate = _extract_json_candidate(html)
    if json_candidate is not None:
        return ParseResult(
            unread_count=json_candidate.unread_count,
            source=json_candidate.source,
            confidence=json_candidate.confidence,
            previews=previews,
        )

    candidates = _collect_strict_candidates(html)

    stripped_text = _strip_markup_for_keyword_search(html)
    near_match = _NEAR_KEYWORD_PATTERN.search(stripped_text)
    if near_match:
        candidates.append(
            _Candidate(
                unread_count=int(near_match.group(1)),
                source="keyword-near-match",
                confidence=0.35,
                priority=10,
            )
        )

    result = _choose_best(candidates)
    if result is not None:
        return ParseResult(
            unread_count=result.unread_count,
            source=result.source,
            confidence=result.confidence,
            previews=previews,
        )
    raise UnreadParseError("Could not parse unread message count from HTML")
