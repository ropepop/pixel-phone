package lv.jolkins.pixelorchestrator.rootexec

import kotlin.test.assertEquals
import org.junit.Test

class ShellEscaperTest {

  @Test
  fun escapesSingleQuotesForShell() {
    val value = "a'b'c"
    val escaped = ShellEscaper.singleQuote(value)
    assertEquals("'a'\"'\"'b'\"'\"'c'", escaped)
  }
}
