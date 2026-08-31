package lv.jolkins.pixelorchestrator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComponentRegistryTest {
  @Test
  fun validGeneratedRegistryLoadsInOrder() {
    val entries = ComponentRegistry.parse(
      """
      {
        "schema": 1,
        "components": [
          {"id":"ssh","startCommand":"start","stopCommand":"stop","healthCommand":"health"},
          {"id":"vpn","startCommand":"start","stopCommand":"stop","healthCommand":"health"}
        ]
      }
      """.trimIndent()
    )

    assertEquals(listOf("ssh", "vpn"), entries.map { it.id })
  }

  @Test
  fun missingEmptyInvalidAndDuplicateRegistriesFailClosed() {
    val invalidDocuments = listOf(
      """{"schema":1,"components":[]}""",
      """{"schema":2,"components":[{"id":"ssh","startCommand":"s","stopCommand":"x","healthCommand":"h"}]}""",
      """{"schema":1,"components":[{"id":"SSH bad","startCommand":"s","stopCommand":"x","healthCommand":"h"}]}""",
      """{"schema":1,"components":[{"id":"ssh","startCommand":"","stopCommand":"x","healthCommand":"h"}]}""",
      """{"schema":1,"components":[{"id":"ssh","startCommand":"s","stopCommand":"x","healthCommand":"h"},{"id":"ssh","startCommand":"s","stopCommand":"x","healthCommand":"h"}]}"""
    )

    invalidDocuments.forEach { raw ->
      assertThrows(IllegalArgumentException::class.java) { ComponentRegistry.parse(raw) }
    }
  }
}
