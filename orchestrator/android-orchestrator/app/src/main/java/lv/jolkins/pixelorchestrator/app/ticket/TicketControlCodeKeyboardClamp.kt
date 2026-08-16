package lv.jolkins.pixelorchestrator.app.ticket

/**
 * Root-shell scripts for the short, request-owned ViVi keyboard suppression
 * lease. The existing root typing helper still reasserts the setting inline;
 * these scripts only establish and restore the request lifetime clamp.
 */
internal object TicketControlCodeKeyboardClamp {
  const val SETTING_NAME = "show_ime_with_hard_keyboard"
  const val ENABLED_INPUT_METHODS_SETTING = "enabled_input_methods"
  const val DEFAULT_INPUT_METHOD_SETTING = "default_input_method"
  const val ABSENT_SETTING = "__ABSENT__"
  const val APPLIED_MARKER = "__APPLIED__"

  data class PreviousState(
    val hardKeyboardSetting: String?,
    val enabledInputMethods: String? = null,
    val defaultInputMethod: String? = null
  )

  fun buildAcquireScript(): String = """
    previous=${'$'}(settings get secure $SETTING_NAME 2>/dev/null) || exit 44
    previous_enabled=${'$'}(settings get secure $ENABLED_INPUT_METHODS_SETTING 2>/dev/null) || exit 44
    previous_default=${'$'}(settings get secure $DEFAULT_INPUT_METHOD_SETTING 2>/dev/null) || exit 44
    ime_target=${'$'}previous_default
    case "${'$'}ime_target" in
      ""|null) ime_target=${'$'}previous_enabled; ime_target=${'$'}{ime_target%%:*} ;;
    esac
    restore_previous() {
      case "${'$'}previous_enabled" in
        ""|null) ;;
        *)
          restore_ifs=${'$'}IFS
          IFS=:
          for restore_ime_id in ${'$'}previous_enabled; do
            [ -n "${'$'}restore_ime_id" ] || continue
            ime enable "${'$'}restore_ime_id" >/dev/null 2>&1 || true
          done
          IFS=${'$'}restore_ifs
          ;;
      esac
      case "${'$'}previous_default" in
        ""|null) ;;
        *)
          ime enable "${'$'}previous_default" >/dev/null 2>&1 || true
          ime set "${'$'}previous_default" >/dev/null 2>&1 || true
          ;;
      esac
      case "${'$'}previous_enabled" in
        ""|null) settings delete secure $ENABLED_INPUT_METHODS_SETTING >/dev/null 2>&1 || true ;;
        *) settings put secure $ENABLED_INPUT_METHODS_SETTING "${'$'}previous_enabled" >/dev/null 2>&1 || true ;;
      esac
      case "${'$'}previous_default" in
        ""|null) settings delete secure $DEFAULT_INPUT_METHOD_SETTING >/dev/null 2>&1 || true ;;
        *) settings put secure $DEFAULT_INPUT_METHOD_SETTING "${'$'}previous_default" >/dev/null 2>&1 || true ;;
      esac
      case "${'$'}previous" in
        ""|null) settings delete secure $SETTING_NAME >/dev/null 2>&1 || true ;;
        *) settings put secure $SETTING_NAME "${'$'}previous" >/dev/null 2>&1 || true ;;
      esac
    }
    restore_on_failure() {
      restore_status=${'$'}?
      if [ "${'$'}restore_status" -ne 0 ]; then
        restore_previous
      fi
      exit "${'$'}restore_status"
    }
    trap restore_on_failure EXIT
    case "${'$'}previous_enabled" in
      ""|null) ;;
      *)
        old_ifs=${'$'}IFS
        IFS=:
        for ime_id in ${'$'}previous_enabled; do
          [ -n "${'$'}ime_id" ] || continue
          ime disable "${'$'}ime_id" >/dev/null 2>&1 || exit 45
        done
        IFS=${'$'}old_ifs
        ;;
    esac
    case "${'$'}previous_enabled" in
      ""|null)
        case "${'$'}ime_target" in
          ""|null) ;;
          *) ime disable "${'$'}ime_target" >/dev/null 2>&1 || exit 45 ;;
        esac
        ;;
    esac
    settings put secure $SETTING_NAME 0 >/dev/null 2>&1 || exit 43
    case "${'$'}previous" in
      ""|null) printf '%s\n' 'previous=$ABSENT_SETTING' ;;
      *) printf '%s\n' "previous=${'$'}previous" ;;
    esac
    case "${'$'}previous_enabled" in
      ""|null) printf '%s\n' 'previous_enabled_input_methods=$ABSENT_SETTING' ;;
      *) printf '%s\n' "previous_enabled_input_methods=${'$'}previous_enabled" ;;
    esac
    case "${'$'}previous_default" in
      ""|null) printf '%s\n' 'previous_default_input_method=$ABSENT_SETTING' ;;
      *) printf '%s\n' "previous_default_input_method=${'$'}previous_default" ;;
    esac
    printf '%s\n' '$APPLIED_MARKER'
    trap - EXIT
    exit 0
  """.trimIndent()

  fun buildRestoreScript(previousState: PreviousState): String {
    val imeIdsToEnable = buildList {
      previousState.enabledInputMethods
        ?.split(':')
        ?.filter(String::isNotBlank)
        ?.forEach(::add)
      previousState.defaultInputMethod?.takeIf(String::isNotBlank)?.let(::add)
    }.distinct()
    val enableIme = imeIdsToEnable.joinToString("\n") { imeId ->
      "ime enable ${shellQuote(imeId)} >/dev/null 2>&1 || true"
    }
    val setDefaultIme = previousState.defaultInputMethod?.let { imeId ->
      "ime set ${shellQuote(imeId)} >/dev/null 2>&1 || true"
    }.orEmpty()
    return """
      $enableIme
      $setDefaultIme
      ${buildRestoreSettingScript(SETTING_NAME, previousState.hardKeyboardSetting)}
      ${buildRestoreSettingScript(ENABLED_INPUT_METHODS_SETTING, previousState.enabledInputMethods)}
      ${buildRestoreSettingScript(DEFAULT_INPUT_METHOD_SETTING, previousState.defaultInputMethod)}
      exit 0
    """.trimIndent()
  }

  fun parseAcquiredState(stdout: String): PreviousState {
    require(stdout.lineSequence().any { it.trim() == APPLIED_MARKER }) {
      "keyboard clamp was not applied"
    }
    val hardKeyboardLine = stdout.lineSequence()
      .map(String::trim)
      .firstOrNull { it.startsWith("previous=") }
      ?: error("keyboard clamp did not report the previous setting")
    return PreviousState(
      hardKeyboardSetting = hardKeyboardLine.removePrefix("previous=")
        .takeUnless { it.isBlank() || it == ABSENT_SETTING },
      enabledInputMethods = parseSetting(stdout, "previous_enabled_input_methods="),
      defaultInputMethod = parseSetting(stdout, "previous_default_input_method=")
    )
  }

  private fun parseSetting(stdout: String, prefix: String): String? {
    return stdout.lineSequence()
      .map(String::trim)
      .firstOrNull { it.startsWith(prefix) }
      ?.removePrefix(prefix)
      ?.takeUnless { it.isBlank() || it == ABSENT_SETTING }
  }

  private fun buildRestoreSettingScript(name: String, value: String?): String {
    if (value == null) {
      return "settings delete secure $name >/dev/null 2>&1 || exit 43"
    }
    return "settings put secure $name ${shellQuote(value)} >/dev/null 2>&1 || exit 43"
  }

  private fun shellQuote(value: String): String {
    return "'${value.replace("'", "'\\''")}'"
  }
}
