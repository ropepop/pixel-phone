package lv.jolkins.pixelorchestrator.rootexec

object ShellEscaper {
  fun singleQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }
}
