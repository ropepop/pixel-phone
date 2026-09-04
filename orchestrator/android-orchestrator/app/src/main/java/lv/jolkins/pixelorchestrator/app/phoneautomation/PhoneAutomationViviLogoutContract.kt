package lv.jolkins.pixelorchestrator.app.phoneautomation

enum class PhoneAutomationViviLogoutSurface {
  PROFILE_LANDING,
  ACCOUNT_DETAILS_BEFORE_SCROLL,
  ACCOUNT_DETAILS_AFTER_SCROLL,
  UNKNOWN
}

enum class PhoneAutomationViviLogoutClickTarget {
  PROFILE_TAB,
  ACCOUNT_CONTROLS,
  LOGOUT
}

/**
 * Purpose-built, fail-closed selectors for ViVi's non-destructive account logout route.
 *
 * The contract deliberately keeps the destructive account-delete control in view as a rejection
 * anchor. It returns only an exact, distinct `Iziet` button and never falls back to a clickable
 * parent, a partial label, or a coordinate near `Dzēst kontu`.
 */
internal object PhoneAutomationViviLogoutContract {
  fun surface(nodes: List<PhoneAutomationVisibleNode>): PhoneAutomationViviLogoutSurface {
    if (bottomTabs(nodes) == null) return PhoneAutomationViviLogoutSurface.UNKNOWN
    if (logoutIndex(nodes) != null) {
      return PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL
    }
    if (accountDetailsBeforeScroll(nodes)) {
      return PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_BEFORE_SCROLL
    }
    if (accountControlsIndex(nodes) != null) {
      return PhoneAutomationViviLogoutSurface.PROFILE_LANDING
    }
    return PhoneAutomationViviLogoutSurface.UNKNOWN
  }

  fun clickIndex(
    nodes: List<PhoneAutomationVisibleNode>,
    target: PhoneAutomationViviLogoutClickTarget
  ): Int? = when (target) {
    PhoneAutomationViviLogoutClickTarget.PROFILE_TAB -> bottomTabs(nodes)?.profileIndex
    PhoneAutomationViviLogoutClickTarget.ACCOUNT_CONTROLS -> accountControlsIndex(nodes)
    PhoneAutomationViviLogoutClickTarget.LOGOUT -> logoutIndex(nodes)
  }

  private fun accountControlsIndex(nodes: List<PhoneAutomationVisibleNode>): Int? {
    if (bottomTabs(nodes) == null) return null
    val nonBottomClickables = nodes.withIndex().filter { (_, node) ->
      node.enabled && node.clickable && (bounds(node.bounds)?.top ?: Int.MAX_VALUE) < BOTTOM_NAV_TOP
    }
    if (nonBottomClickables.size != 1) return null
    val candidate = nonBottomClickables.single()
    val node = candidate.value
    if (node.className != "android.widget.Button" || visibleLabels(node).isNotEmpty() ||
      bounds(node.bounds) != PROFILE_ACCOUNT_BUTTON_BOUNDS
    ) return null
    return candidate.index
  }

  private fun accountDetailsBeforeScroll(nodes: List<PhoneAutomationVisibleNode>): Boolean {
    if (!hasExactClickableAnchor(nodes, "pievienot karti", ADD_CARD_BOUNDS) ||
      !hasExactClickableAnchor(
        nodes,
        "ievadit atlaizu kartes datus",
        ENTER_DISCOUNT_CARD_BOUNDS
      )
    ) return false
    return nodes.count { node ->
      node.enabled && node.className == "android.widget.EditText"
    } == 3
  }

  private fun logoutIndex(nodes: List<PhoneAutomationVisibleNode>): Int? {
    if (!hasGuardedPostScrollAnchor(
        nodes,
        "pievienot papildu e-pastu",
        ADD_ADDITIONAL_EMAIL_BOUNDS
      )
    ) return null
    val logout = nodes.withIndex().filter { (_, node) ->
      guardedPostScrollButton(node, "iziet", LOGOUT_BOUNDS)
    }
    val delete = nodes.withIndex().filter { (_, node) ->
      guardedPostScrollButton(node, "dzest kontu", DELETE_ACCOUNT_BOUNDS)
    }
    if (logout.size != 1 || delete.size != 1) return null
    val logoutCandidate = logout.single()
    val deleteCandidate = delete.single()
    if (logoutCandidate.index == deleteCandidate.index) return null
    val logoutBounds = bounds(logoutCandidate.value.bounds) ?: return null
    val deleteBounds = bounds(deleteCandidate.value.bounds) ?: return null
    if (logoutBounds.bottom >= deleteBounds.top || logoutBounds.overlaps(deleteBounds) ||
      deleteBounds.bottom >= BOTTOM_NAV_VISIBLE_TOP
    ) return null
    return logoutCandidate.index
  }

  private fun guardedPostScrollButton(
    node: PhoneAutomationVisibleNode,
    expectedLabel: String,
    expectedBounds: Bounds
  ): Boolean = node.enabled && node.clickable &&
    node.className == "android.widget.Button" &&
    hasExactVisibleLabel(node, expectedLabel) &&
    matchesGuardedPostScrollBounds(node.bounds, expectedBounds)

  private fun hasGuardedPostScrollAnchor(
    nodes: List<PhoneAutomationVisibleNode>,
    expectedLabel: String,
    expectedBounds: Bounds
  ): Boolean = nodes.count { node ->
    node.enabled && node.clickable && hasExactVisibleLabel(node, expectedLabel) &&
      matchesGuardedPostScrollBounds(node.bounds, expectedBounds)
  } == 1

  private fun matchesGuardedPostScrollBounds(value: String, expected: Bounds): Boolean {
    val actual = bounds(value) ?: return false
    val topDelta = actual.top.toLong() - expected.top.toLong()
    val bottomDelta = actual.bottom.toLong() - expected.bottom.toLong()
    return actual.left == expected.left && actual.right == expected.right &&
      kotlin.math.abs(topDelta) <= POST_SCROLL_VERTICAL_TOLERANCE_PIXELS &&
      kotlin.math.abs(bottomDelta) <= POST_SCROLL_VERTICAL_TOLERANCE_PIXELS &&
      kotlin.math.abs(topDelta - bottomDelta) <= POST_SCROLL_HEIGHT_TOLERANCE_PIXELS
  }

  private fun hasExactClickableAnchor(
    nodes: List<PhoneAutomationVisibleNode>,
    expectedLabel: String,
    expectedBounds: Bounds
  ): Boolean = nodes.count { node ->
    node.enabled && node.clickable && hasExactVisibleLabel(node, expectedLabel) &&
      bounds(node.bounds) == expectedBounds
  } == 1

  private fun bottomTabs(nodes: List<PhoneAutomationVisibleNode>): BottomTabs? {
    fun exactTabIndex(label: String, ordinal: Int, expectedBounds: Bounds): Int? {
      val candidates = nodes.withIndex().filter { (_, node) ->
        node.enabled && node.clickable && bounds(node.bounds) == expectedBounds &&
          hasExactVisibleLabel(node, "$label $ordinal. cilne no 4")
      }
      return candidates.singleOrNull()?.index
    }
    val home = exactTabIndex("home", 1, HOME_TAB_BOUNDS) ?: return null
    val tickets = exactTabIndex("tickets", 2, TICKETS_TAB_BOUNDS) ?: return null
    // ViVi exposes its Profile tab with the stable semantic label `user` on the live Latvian
    // build. Keep PROFILE_TAB as the internal route name, but match the exact device semantics.
    val profile = exactTabIndex("user", 3, PROFILE_TAB_BOUNDS) ?: return null
    val menu = exactTabIndex("menu", 4, MENU_TAB_BOUNDS) ?: return null
    if (setOf(home, tickets, profile, menu).size != 4) return null
    return BottomTabs(profile)
  }

  private fun visibleLabels(node: PhoneAutomationVisibleNode): Set<String> =
    listOf(node.text, node.contentDescription, node.hint)
      .map(::normalize)
      .filter(String::isNotBlank)
      .toSet()

  private fun hasExactVisibleLabel(
    node: PhoneAutomationVisibleNode,
    expected: String
  ): Boolean = visibleLabels(node) == setOf(expected)

  private fun normalize(value: String): String = value
    .lowercase()
    .replace('\u00a0', ' ')
    .replace('ā', 'a')
    .replace('č', 'c')
    .replace('ē', 'e')
    .replace('ģ', 'g')
    .replace('ī', 'i')
    .replace('ķ', 'k')
    .replace('ļ', 'l')
    .replace('ņ', 'n')
    .replace('š', 's')
    .replace('ū', 'u')
    .replace('ž', 'z')
    .replace(Regex("""\s+"""), " ")
    .trim()

  private fun bounds(value: String): Bounds? {
    val match = BOUNDS_REGEX.matchEntire(value) ?: return null
    val values = match.groupValues.drop(1).map(String::toIntOrNull)
    if (values.any { it == null }) return null
    return Bounds(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
  }

  private data class BottomTabs(val profileIndex: Int)

  private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun overlaps(other: Bounds): Boolean =
      left < other.right && right > other.left && top < other.bottom && bottom > other.top
  }

  private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
  private const val BOTTOM_NAV_TOP = 2100
  private const val BOTTOM_NAV_VISIBLE_TOP = 2209
  private const val POST_SCROLL_VERTICAL_TOLERANCE_PIXELS = 2
  private const val POST_SCROLL_HEIGHT_TOLERANCE_PIXELS = 1
  private val HOME_TAB_BOUNDS = Bounds(0, 2209, 270, 2361)
  private val TICKETS_TAB_BOUNDS = Bounds(270, 2209, 540, 2361)
  private val PROFILE_TAB_BOUNDS = Bounds(540, 2209, 810, 2361)
  private val MENU_TAB_BOUNDS = Bounds(810, 2209, 1080, 2361)
  private val PROFILE_ACCOUNT_BUTTON_BOUNDS = Bounds(414, 1054, 666, 1306)
  private val ADD_CARD_BOUNDS = Bounds(79, 604, 1001, 724)
  private val ENTER_DISCOUNT_CARD_BOUNDS = Bounds(79, 1916, 1001, 2037)
  // Accessibility reports full-display coordinates. Offset or cropped-frame coordinates must
  // never be used for these account-detail targets.
  private val ADD_ADDITIONAL_EMAIL_BOUNDS = Bounds(79, 1721, 1001, 1842)
  private val LOGOUT_BOUNDS = Bounds(456, 1920, 624, 2046)
  private val DELETE_ACCOUNT_BOUNDS = Bounds(270, 2086, 810, 2208)
}
