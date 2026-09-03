package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAutomationViviLogoutContractTest {
  @Test
  fun selectsOnlyTheExactProfileTabFromTheCompleteBottomNavigation() {
    val nodes = bottomTabs()

    assertEquals(
      2,
      PhoneAutomationViviLogoutContract.clickIndex(
        nodes,
        PhoneAutomationViviLogoutClickTarget.PROFILE_TAB
      )
    )
    assertNull(
      PhoneAutomationViviLogoutContract.clickIndex(
        nodes.dropLast(1),
        PhoneAutomationViviLogoutClickTarget.PROFILE_TAB
      )
    )
    listOf(
      "profile\n3. cilne no 4",
      "user settings\n3. cilne no 4",
      "user\n2. cilne no 4",
      "user\n3. cilne no 4 extra"
    ).forEach { lookalike ->
      val changed = nodes.map { node ->
        if (node.contentDescription.startsWith("user")) {
          node.copy(contentDescription = lookalike)
        } else {
          node
        }
      }
      assertNull(
        PhoneAutomationViviLogoutContract.clickIndex(
          changed,
          PhoneAutomationViviLogoutClickTarget.PROFILE_TAB
        )
      )
    }
  }

  @Test
  fun profileLandingRequiresOneExactEmptyCentralButton() {
    val nodes = bottomTabs() + node(
      className = "android.widget.Button",
      bounds = "[414,1054][666,1306]",
      clickable = true
    )

    assertEquals(
      PhoneAutomationViviLogoutSurface.PROFILE_LANDING,
      PhoneAutomationViviLogoutContract.surface(nodes)
    )
    assertEquals(
      4,
      PhoneAutomationViviLogoutContract.clickIndex(
        nodes,
        PhoneAutomationViviLogoutClickTarget.ACCOUNT_CONTROLS
      )
    )
    assertEquals(
      PhoneAutomationViviLogoutSurface.UNKNOWN,
      PhoneAutomationViviLogoutContract.surface(
        nodes + node(text = "Unexpected", bounds = "[20,300][400,380]", clickable = true)
      )
    )
  }

  @Test
  fun accountDetailsBeforeScrollRequiresBothAnchorsAndExactlyThreeFields() {
    val nodes = accountBeforeScroll()

    assertEquals(
      PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_BEFORE_SCROLL,
      PhoneAutomationViviLogoutContract.surface(nodes)
    )
    assertEquals(
      PhoneAutomationViviLogoutSurface.UNKNOWN,
      PhoneAutomationViviLogoutContract.surface(nodes.dropLast(1))
    )
  }

  @Test
  fun exactIzietIsSelectedOnlyWithTheDistinctDeleteGuardBelowIt() {
    val nodes = accountAfterScroll()

    assertEquals(
      PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL,
      PhoneAutomationViviLogoutContract.surface(nodes)
    )
    assertEquals(
      5,
      PhoneAutomationViviLogoutContract.clickIndex(
        nodes,
        PhoneAutomationViviLogoutClickTarget.LOGOUT
      )
    )
  }

  @Test
  fun accountDeleteCanNeverBecomeTheLogoutTarget() {
    val withoutLogout = accountAfterScroll().filterNot { it.contentDescription == "Iziet" }
    val deleteAtLogoutBounds = accountAfterScroll().map { node ->
      if (node.contentDescription == "Dzēst kontu") node.copy(bounds = "[456,1649][624,1775]") else node
    }
    val duplicateLogout = accountAfterScroll() + node(
      contentDescription = "Iziet",
      className = "android.widget.Button",
      bounds = "[456,1649][624,1775]",
      clickable = true
    )

    listOf(withoutLogout, deleteAtLogoutBounds, duplicateLogout).forEach { nodes ->
      assertNull(
        PhoneAutomationViviLogoutContract.clickIndex(
          nodes,
          PhoneAutomationViviLogoutClickTarget.LOGOUT
        )
      )
      assertEquals(
        PhoneAutomationViviLogoutSurface.UNKNOWN,
        PhoneAutomationViviLogoutContract.surface(nodes)
      )
    }
  }

  @Test
  fun partialOrLookalikeLogoutLabelsFailClosed() {
    listOf("Iziet no konta", "IZIET!", "Dzēst kontu", "iziet").forEach { label ->
      val nodes = accountAfterScroll().map { node ->
        if (node.contentDescription == "Iziet") node.copy(
          text = if (label == "iziet") label else "",
          contentDescription = if (label == "iziet") "Iziet" else label,
          className = if (label == "iziet") "android.view.View" else node.className
        ) else node
      }
      assertNull(
        PhoneAutomationViviLogoutContract.clickIndex(
          nodes,
          PhoneAutomationViviLogoutClickTarget.LOGOUT
        )
      )
    }
  }

  private fun accountBeforeScroll(): List<PhoneAutomationVisibleNode> = bottomTabs() + listOf(
    node(
      contentDescription = "Pievienot karti",
      bounds = "[79,604][1001,724]",
      clickable = true
    ),
    node(
      contentDescription = "Ievadīt atlaižu kartes datus",
      bounds = "[79,1916][1001,2037]",
      clickable = true
    ),
    node(className = "android.widget.EditText", bounds = "[79,800][1001,900]"),
    node(className = "android.widget.EditText", bounds = "[79,950][1001,1050]"),
    node(className = "android.widget.EditText", bounds = "[79,1100][1001,1200]")
  )

  private fun accountAfterScroll(): List<PhoneAutomationVisibleNode> = bottomTabs() + listOf(
    node(
      contentDescription = "Pievienot papildu e-pastu",
      bounds = "[79,1449][1001,1570]",
      clickable = true
    ),
    node(
      contentDescription = "Iziet",
      className = "android.widget.Button",
      bounds = "[456,1649][624,1775]",
      clickable = true
    ),
    node(
      contentDescription = "Dzēst kontu",
      className = "android.widget.Button",
      bounds = "[270,1814][810,1945]",
      clickable = true
    )
  )

  private fun bottomTabs(): List<PhoneAutomationVisibleNode> = listOf(
    node(contentDescription = "Home\n1. cilne no 4", bounds = "[0,2209][270,2361]", clickable = true),
    node(contentDescription = "Tickets\n2. cilne no 4", bounds = "[270,2209][540,2361]", clickable = true),
    node(contentDescription = "user\n3.\u00a0cilne no\u00a04", bounds = "[540,2209][810,2361]", clickable = true),
    node(contentDescription = "Menu\n4. cilne no 4", bounds = "[810,2209][1080,2361]", clickable = true)
  )

  private fun node(
    text: String = "",
    contentDescription: String = "",
    className: String = "android.view.View",
    bounds: String,
    clickable: Boolean = false,
    enabled: Boolean = true
  ) = PhoneAutomationVisibleNode(
    text = text,
    resourceId = "",
    contentDescription = contentDescription,
    className = className,
    bounds = bounds,
    clickable = clickable,
    enabled = enabled,
    focused = false,
    editable = className == "android.widget.EditText",
    focusable = className == "android.widget.EditText",
    hint = "",
    password = false
  )
}
