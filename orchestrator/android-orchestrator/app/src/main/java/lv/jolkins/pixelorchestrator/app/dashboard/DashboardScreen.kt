package lv.jolkins.pixelorchestrator.app.dashboard

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyCluster
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationDispatchInterval

private enum class Confirmation {
  BOOTSTRAP,
  STOP_ALL,
  CLEANUP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardScreen(
  state: DashboardUiState,
  onAction: (DashboardAction) -> Unit,
  modifier: Modifier = Modifier
) {
  var stackExpanded by rememberSaveable { mutableStateOf(true) }
  var touchDetailExpanded by rememberSaveable { mutableStateOf(false) }
  var reliabilityExpanded by rememberSaveable { mutableStateOf(false) }
  var activityExpanded by rememberSaveable { mutableStateOf(true) }
  var operationDetailExpanded by rememberSaveable { mutableStateOf(false) }
  var confirmation by rememberSaveable { mutableStateOf<Confirmation?>(null) }
  val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
  val largeText = LocalDensity.current.fontScale >= 1.5f

  confirmation?.let { requested ->
    ConfirmationDialog(
      confirmation = requested,
      onDismiss = { confirmation = null },
      onConfirm = {
        confirmation = null
        onAction(
          when (requested) {
            Confirmation.BOOTSTRAP -> DashboardAction.Bootstrap
            Confirmation.STOP_ALL -> DashboardAction.StopAll
            Confirmation.CLEANUP -> DashboardAction.RunCleanup
          }
        )
      }
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 2.dp
      ) {
        if (largeText) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(horizontal = 20.dp, vertical = 12.dp)
          ) {
            HeaderIdentity(state, Modifier.fillMaxWidth(), largeText = true)
            Spacer(Modifier.height(8.dp))
            HealthBadge(state.healthLevel, motionEnabled)
          }
        } else Row(
          modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          HeaderIdentity(state, Modifier.weight(1f), largeText = false)
          Spacer(Modifier.width(12.dp))
          HealthBadge(state.healthLevel, motionEnabled)
        }
      }
    }
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentPadding = PaddingValues(bottom = 40.dp)
    ) {
      item(key = "overview") {
        OverviewBlock(state)
      }

      item(key = "stack") {
        DashboardSection(
          title = "Stack status",
          description = healthFreshness(state.healthGeneratedAtMillis),
          expanded = stackExpanded,
          onToggle = { stackExpanded = !stackExpanded },
          motionEnabled = motionEnabled
        ) {
          state.modules.forEach { module ->
            ModuleStatusRow(module)
          }
          OutlinedButton(
            onClick = { onAction(DashboardAction.RunHealthCheck) },
            enabled = !state.operation.inProgress,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
          ) {
            Text("Refresh full health")
          }
          OperationFeedback(state.operation, "health", operationDetailExpanded) {
            operationDetailExpanded = !operationDetailExpanded
          }
        }
      }

      item(key = "automation") {
        DashboardSection(
          title = "Speedtest and CellMapper",
          description = "Foreground automation, dispatch timing, and return behavior."
        ) {
          if (state.phoneUiPolicy.protectedHandoff) {
            NoticeRow(
              icon = Icons.Rounded.Lock,
              title = "Protected handoff in progress",
              detail = "Timing and companion controls stay locked until Speedtest safely returns."
            )
          }
          ToggleRow(
            title = "Speedtest automation",
            detail = state.phoneAutomation.runtimeSummary(),
            checked = state.phoneAutomation.enabled,
            enabled = state.phoneUiPolicy.phoneAutomationToggleEnabled,
            onCheckedChange = { onAction(DashboardAction.SetPhoneAutomation(it)) }
          )
          ToggleRow(
            title = "Maintain CellMapper",
            detail = "Keep recording alongside Speedtest.",
            checked = state.phoneAutomation.maintainCellMapper,
            enabled = state.phoneUiPolicy.cellMapperToggleEnabled,
            onCheckedChange = { onAction(DashboardAction.SetMaintainCellMapper(it)) }
          )
          AnimatedSectionVisibility(
            visible = state.phoneAutomation.enabled,
            motionEnabled = motionEnabled
          ) {
            ToggleRow(
              title = "Return to orchestrator",
              detail = "Come back here after foreground work.",
              checked = state.phoneAutomation.returnToOrchestratorAfterForegroundWork,
              enabled = state.phoneUiPolicy.returnToOrchestratorToggleEnabled,
              onCheckedChange = { onAction(DashboardAction.SetReturnToOrchestrator(it)) }
            )
          }
          IntervalSlider(
            selected = state.phoneAutomation.dispatchInterval,
            enabled = state.phoneUiPolicy.dispatchIntervalEnabled,
            onSelected = { onAction(DashboardAction.SetDispatchInterval(it)) }
          )
          DetailLine("Setup", state.phoneAutomation.setupSummary())
          DetailLine("Live status", state.phoneAutomation.runtimeSummary())
        }
      }

      item(key = "ticket") {
        DashboardSection(
          title = "Ticket service readiness",
          description = "Keep the local service and public tunnel ready without opening ViVi or capture."
        ) {
          ToggleRow(
            title = "Keep ticket service ready",
            detail = state.ticketService.statusSummary(),
            checked = state.ticketService.enabled,
            onCheckedChange = { onAction(DashboardAction.SetTicketService(it)) }
          )
        }
      }

      item(key = "brightness") {
        DashboardSection(
          title = "Touch brightness",
          description = "Let physical touch and the power button control a zero-brightness panel."
        ) {
          ToggleRow(
            title = "Touch brightness mode",
            detail = state.phoneAutomation.touchBrightnessRuntimeSummary(),
            checked = state.phoneAutomation.touchBrightnessEnabled,
            enabled = state.phoneUiPolicy.touchBrightnessToggleEnabled,
            onCheckedChange = { onAction(DashboardAction.SetTouchBrightness(it)) }
          )
          ExpandableDetailRow(
            title = if (touchDetailExpanded) "Hide technical detail" else "Show technical detail",
            expanded = touchDetailExpanded,
            onClick = { touchDetailExpanded = !touchDetailExpanded }
          )
          AnimatedSectionVisibility(touchDetailExpanded, motionEnabled) {
            SelectableDetail(
              state.phoneAutomation.touchBrightnessDebugDetail.ifBlank {
                "Waiting for live touch data"
              }
            )
          }
        }
      }

      item(key = "performance") {
        DashboardSection(
          title = "CPU and GPU limits",
          description = "Soft performance ceilings with live thermal and battery context."
        ) {
          ToggleRow(
            title = "Apply saved limits",
            detail = state.cpuFrequency.runtimeSummary(),
            checked = state.cpuFrequency.enabled,
            onCheckedChange = { onAction(DashboardAction.SetCpuFrequency(it)) }
          )
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Rounded.Build, contentDescription = null)
              Spacer(Modifier.width(12.dp))
              Text(
                "${state.cpuLive.thermalStatusLabel()} thermal  •  ${if (state.cpuLive.charging) "Charging" else "On battery"}  •  ${state.cpuLive.batteryTempCelsius()}",
                style = MaterialTheme.typography.bodyMedium
              )
            }
          }
          CpuFrequencyCluster.entries.forEach { cluster ->
            val policy = state.cpuLive.policy(cluster)
            FrequencySlider(
              label = "${cluster.displayName} CPU",
              supportingText = "Current ${CpuFrequencySettingsSnapshot.formatKHz(policy.currentFreqKHz)}  •  limit ${CpuFrequencySettingsSnapshot.formatKHz(policy.appliedMaxFreqKHz)}  •  stock ${CpuFrequencySettingsSnapshot.formatKHz(policy.stockMaxFreqKHz)}",
              available = policy.availableFreqsKHz,
              selected = state.cpuFrequency.desiredCap(cluster)
                ?: policy.appliedMaxFreqKHz.takeIf { it > 0L }
                ?: policy.stockMaxFreqKHz,
              onSelected = { onAction(DashboardAction.SetCpuCap(cluster, it)) }
            )
          }
          val gpu = state.cpuLive.gpu
          FrequencySlider(
            label = "Mali GPU",
            supportingText = if (gpu.available) {
              "Current ${CpuFrequencySettingsSnapshot.formatKHz(gpu.currentFreqKHz)}  •  limit ${CpuFrequencySettingsSnapshot.formatKHz(gpu.appliedMaxFreqKHz)}  •  ${gpu.governorLabel()}  •  ${gpu.utilizationLabel()}"
            } else {
              "Live GPU controls are unavailable on this device."
            },
            available = gpu.availableFreqsKHz,
            selected = state.cpuFrequency.desiredGpuCap()
              ?: gpu.appliedMaxFreqKHz.takeIf { it > 0L }
              ?: gpu.stockMaxFreqKHz,
            onSelected = { onAction(DashboardAction.SetGpuCap(it)) }
          )
          OutlinedButton(
            onClick = { onAction(DashboardAction.RestoreStockFrequencies) },
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
          ) {
            Text("Restore stock limits now")
          }
        }
      }

      item(key = "reliability") {
        DashboardSection(
          title = "Permissions and reliability",
          description = if (state.reliability.exactAlarmGranted) {
            "Cleanup schedule: exact idle alarm available."
          } else {
            "Cleanup schedule: approximate idle fallback; exact alarm access is missing."
          },
          expanded = reliabilityExpanded,
          onToggle = { reliabilityExpanded = !reliabilityExpanded },
          motionEnabled = motionEnabled
        ) {
          ReliabilityRow(
            title = "Battery access",
            value = if (state.reliability.batteryUnrestricted) "Unrestricted" else "Restricted",
            healthy = state.reliability.batteryUnrestricted,
            action = "Open battery settings",
            actionEnabled = !state.reliability.batteryUnrestricted,
            onClick = { onAction(DashboardAction.OpenBatterySettings) }
          )
          ReliabilityRow(
            title = "Exact alarms",
            value = if (state.reliability.exactAlarmGranted) "Granted" else "Missing",
            healthy = state.reliability.exactAlarmGranted,
            action = "Open alarm settings",
            actionEnabled = !state.reliability.exactAlarmGranted,
            onClick = { onAction(DashboardAction.OpenExactAlarmSettings) }
          )
          SettingsButton("Accessibility settings") {
            onAction(DashboardAction.OpenAccessibilitySettings)
          }
          SettingsButton("Notification access settings") {
            onAction(DashboardAction.OpenNotificationAccessSettings)
          }
        }
      }

      item(key = "operations") {
        DashboardSection(
          title = "Operations",
          description = "Routine controls keep results beside the action that started them."
        ) {
          OperationButton(
            title = "Start all services",
            actionKey = "start_all",
            operation = state.operation,
            onClick = { onAction(DashboardAction.StartAll) }
          )
          OperationButton(
            title = "Run full health check",
            actionKey = "health",
            operation = state.operation,
            onClick = { onAction(DashboardAction.RunHealthCheck) }
          )
          OperationButton(
            title = "Sync DDNS now",
            actionKey = "sync_ddns",
            operation = state.operation,
            onClick = { onAction(DashboardAction.SyncDdns) }
          )
          OperationFeedback(
            operation = state.operation,
            actionPrefix = "",
            detailExpanded = operationDetailExpanded,
            onToggleDetail = { operationDetailExpanded = !operationDetailExpanded }
          )
        }
      }

      item(key = "high_impact") {
        HighImpactSection(
          operation = state.operation,
          onBootstrap = { confirmation = Confirmation.BOOTSTRAP },
          onStopAll = { confirmation = Confirmation.STOP_ALL },
          onCleanup = { confirmation = Confirmation.CLEANUP }
        )
      }

      item(key = "activity") {
        DashboardSection(
          title = "Recent activity",
          description = "Latest 20 safe orchestrator events, including this session.",
          expanded = activityExpanded,
          onToggle = { activityExpanded = !activityExpanded },
          motionEnabled = motionEnabled
        ) {
          if (state.visibleActivity.isEmpty()) {
            Text(
              "No activity yet.",
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            state.visibleActivity.forEach { ActivityRow(it) }
          }
        }
      }

      item(key = "diagnostics") {
        DashboardSection(
          title = "Diagnostics",
          description = "Create a redacted support bundle without including secrets."
        ) {
          OperationButton(
            title = "Create redacted support bundle",
            actionKey = "export_bundle",
            operation = state.operation,
            onClick = { onAction(DashboardAction.ExportSupportBundle) }
          )
          OperationFeedback(state.operation, "export_bundle", operationDetailExpanded) {
            operationDetailExpanded = !operationDetailExpanded
          }
        }
      }
    }
  }
}

@Composable
private fun HeaderIdentity(
  state: DashboardUiState,
  modifier: Modifier,
  largeText: Boolean
) {
  Column(modifier) {
    Text(
      text = if (largeText) "Pixel\nOrchestrator" else "Pixel Orchestrator",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2
    )
    Text(
      text = state.buildIdentity,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = if (largeText) 4 else 2,
      overflow = if (largeText) TextOverflow.Clip else TextOverflow.Ellipsis
    )
  }
}

@Composable
private fun OverviewBlock(state: DashboardUiState) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 22.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    StatusIcon(state.healthLevel, Modifier.size(44.dp))
    Spacer(Modifier.width(16.dp))
    Column {
      Text(
        text = healthTitle(state.healthLevel),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
      )
      Text(
        text = healthSubtitle(state.healthLevel),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun DashboardSection(
  title: String,
  description: String,
  expanded: Boolean? = null,
  onToggle: (() -> Unit)? = null,
  motionEnabled: Boolean = true,
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp)
      .animateContentSize(animationSpec = tween(if (motionEnabled) 220 else 0))
  ) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
        .padding(top = 22.dp, bottom = 14.dp)
        .semantics { heading() },
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(3.dp))
        Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      if (expanded != null) {
        Icon(
          imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse $title" else "Expand $title"
        )
      }
    }
    if (expanded == null) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    } else {
      AnimatedSectionVisibility(expanded, motionEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
private fun AnimatedSectionVisibility(
  visible: Boolean,
  motionEnabled: Boolean,
  content: @Composable () -> Unit
) {
  if (!motionEnabled) {
    if (visible) content()
    return
  }
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(180)),
    exit = fadeOut(tween(120))
  ) {
    content()
  }
}

@Composable
private fun HealthBadge(level: DashboardHealthLevel, motionEnabled: Boolean) {
  Surface(
    color = statusContainerColor(level),
    shape = CircleShape,
    modifier = Modifier.semantics { stateDescription = healthTitle(level) }
  ) {
    Box(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
      if (motionEnabled) {
        Crossfade(targetState = level, animationSpec = tween(180), label = "health badge") {
          Text(healthShortLabel(it), style = MaterialTheme.typography.labelMedium)
        }
      } else {
        Text(healthShortLabel(level), style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
private fun StatusIcon(level: DashboardHealthLevel, modifier: Modifier = Modifier) {
  Surface(
    shape = CircleShape,
    color = statusContainerColor(level),
    modifier = modifier
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = when (level) {
          DashboardHealthLevel.HEALTHY -> Icons.Rounded.CheckCircle
          DashboardHealthLevel.DEGRADED, DashboardHealthLevel.STALE -> Icons.Rounded.Warning
          DashboardHealthLevel.FAILED -> Icons.Rounded.Clear
          DashboardHealthLevel.UNKNOWN -> Icons.Rounded.Info
        },
        contentDescription = healthTitle(level),
        tint = statusContentColor(level),
        modifier = Modifier.size(25.dp)
      )
    }
  }
}

@Composable
private fun ModuleStatusRow(module: DashboardModuleStatus) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .padding(vertical = 5.dp),
    verticalAlignment = Alignment.Top
  ) {
    val level = when {
      module.healthy == true -> DashboardHealthLevel.HEALTHY
      module.status.equals("disabled", ignoreCase = true) -> DashboardHealthLevel.UNKNOWN
      module.healthy == false -> DashboardHealthLevel.DEGRADED
      else -> DashboardHealthLevel.UNKNOWN
    }
    StatusIcon(level, Modifier.size(32.dp))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(module.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
          module.status,
          style = MaterialTheme.typography.labelLarge,
          color = statusContentColor(level)
        )
      }
      if (module.details.isNotEmpty()) {
        Text(
          module.details.entries.joinToString("  •  ") { "${it.key.replace('_', ' ')}: ${it.value}" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun ToggleRow(
  title: String,
  detail: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clip(RoundedCornerShape(16.dp))
      .clickable(enabled = enabled) { onCheckedChange(!checked) }
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(Modifier.width(12.dp))
    Switch(
      checked = checked,
      enabled = enabled,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.semantics { contentDescription = title }
    )
  }
}

@Composable
private fun IntervalSlider(
  selected: PhoneAutomationDispatchInterval,
  enabled: Boolean,
  onSelected: (PhoneAutomationDispatchInterval) -> Unit
) {
  val externalValue = selected.sliderIndex.toFloat()
  var draft by remember { mutableFloatStateOf(externalValue) }
  var dragging by remember { mutableStateOf(false) }
  LaunchedEffect(externalValue, dragging) {
    if (!dragging) draft = externalValue
  }
  Column {
    Row(Modifier.fillMaxWidth()) {
      Text("Dispatch timing", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(
        PhoneAutomationDispatchInterval.fromSliderIndex(draft.roundToInt()).sliderLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
      )
    }
    Slider(
      value = draft,
      onValueChange = {
        dragging = true
        draft = it
      },
      onValueChangeFinished = {
        val value = PhoneAutomationDispatchInterval.fromSliderIndex(draft.roundToInt())
        dragging = false
        onSelected(value)
      },
      valueRange = 0f..PhoneAutomationDispatchInterval.entries.lastIndex.toFloat(),
      steps = (PhoneAutomationDispatchInterval.entries.size - 2).coerceAtLeast(0),
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = "Speedtest dispatch timing" }
    )
  }
}

@Composable
private fun FrequencySlider(
  label: String,
  supportingText: String,
  available: List<Long>,
  selected: Long,
  onSelected: (Long) -> Unit
) {
  val externalIndex = nearestFrequencyIndex(available, selected).toFloat()
  var draft by remember(label, available) { mutableFloatStateOf(externalIndex) }
  var dragging by remember(label) { mutableStateOf(false) }
  LaunchedEffect(externalIndex, dragging) {
    if (!dragging) draft = externalIndex
  }
  Column {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      if (available.isNotEmpty()) {
        Text(
          CpuFrequencySettingsSnapshot.formatKHz(
            available[draft.roundToInt().coerceIn(0, available.lastIndex)]
          ),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
    Text(
      supportingText,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
      value = draft.coerceIn(0f, available.lastIndex.coerceAtLeast(0).toFloat()),
      onValueChange = {
        dragging = true
        draft = it
      },
      onValueChangeFinished = {
        if (available.isNotEmpty()) {
          val chosen = available[draft.roundToInt().coerceIn(0, available.lastIndex)]
          dragging = false
          onSelected(chosen)
        }
      },
      valueRange = 0f..available.lastIndex.coerceAtLeast(1).toFloat(),
      steps = (available.size - 2).coerceAtLeast(0),
      enabled = available.size > 1,
      modifier = Modifier.semantics { contentDescription = "$label frequency limit" }
    )
  }
}

@Composable
private fun DetailLine(label: String, value: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun NoticeRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  detail: String
) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
      Spacer(Modifier.width(12.dp))
      Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun ExpandableDetailRow(title: String, expanded: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(Icons.Rounded.Info, contentDescription = null)
    Spacer(Modifier.width(12.dp))
    Text(title, modifier = Modifier.weight(1f))
    Icon(
      if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
      contentDescription = null
    )
  }
}

@Composable
private fun SelectableDetail(detail: String) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    SelectionContainer {
      Text(
        text = detail,
        modifier = Modifier.padding(14.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun ReliabilityRow(
  title: String,
  value: String,
  healthy: Boolean,
  action: String,
  actionEnabled: Boolean,
  onClick: () -> Unit
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Icon(
      if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
      contentDescription = if (healthy) "Available" else "Needs attention",
      tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onClick, enabled = actionEnabled) { Text(action) }
  }
}

@Composable
private fun SettingsButton(title: String, onClick: () -> Unit) {
  OutlinedButton(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
  ) {
    Icon(Icons.Rounded.Settings, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text(title)
  }
}

@Composable
private fun OperationButton(
  title: String,
  actionKey: String,
  operation: DashboardOperationState,
  onClick: () -> Unit
) {
  FilledTonalButton(
    onClick = onClick,
    enabled = !operation.inProgress,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
  ) {
    Text(title, modifier = Modifier.weight(1f))
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
  }
  if (operation.inProgress && operation.action == actionKey) {
    LinearProgressIndicator(
      modifier = Modifier
        .fillMaxWidth()
        .semantics { contentDescription = "$title in progress" }
    )
  }
  if (!operation.inProgress && operation.action == actionKey) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.Top
    ) {
      Icon(
        imageVector = if (operation.status == DashboardOperationStatus.SUCCEEDED) {
          Icons.Rounded.CheckCircle
        } else {
          Icons.Rounded.Clear
        },
        contentDescription = if (operation.status == DashboardOperationStatus.SUCCEEDED) {
          "Succeeded"
        } else {
          "Failed"
        },
        tint = if (operation.status == DashboardOperationStatus.SUCCEEDED) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.error
        },
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.width(8.dp))
      Text(operation.message, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun OperationFeedback(
  operation: DashboardOperationState,
  actionPrefix: String,
  detailExpanded: Boolean,
  onToggleDetail: () -> Unit
) {
  if (operation.status == DashboardOperationStatus.IDLE) return
  if (actionPrefix.isNotBlank() && !operation.action.startsWith(actionPrefix)) return
  AnimatedContent(
    targetState = operation.status,
    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
    label = "operation result"
  ) { status ->
    Surface(
      color = when (status) {
        DashboardOperationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        DashboardOperationStatus.SUCCEEDED -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
      }.copy(alpha = 0.75f),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            when (status) {
              DashboardOperationStatus.SUCCEEDED -> Icons.Rounded.CheckCircle
              DashboardOperationStatus.FAILED -> Icons.Rounded.Clear
              else -> Icons.Rounded.DateRange
            },
            contentDescription = status.name.lowercase()
          )
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text(operation.title, fontWeight = FontWeight.SemiBold)
            Text(operation.message, style = MaterialTheme.typography.bodySmall)
          }
        }
        if (operation.detail.isNotBlank()) {
          TextButton(onClick = onToggleDetail) {
            Text(if (detailExpanded) "Hide complete detail" else "Show complete detail")
          }
          if (detailExpanded) SelectableDetail(operation.detail)
        }
      }
    }
  }
}

@Composable
private fun HighImpactSection(
  operation: DashboardOperationState,
  onBootstrap: () -> Unit,
  onStopAll: () -> Unit,
  onCleanup: () -> Unit
) {
  Surface(
    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 10.dp)
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Column {
          Text("High-impact actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
          Text(
            "These actions can interrupt services or remove disposable residue.",
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }
      OutlinedButton(
        onClick = onBootstrap,
        enabled = !operation.inProgress,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
      ) { Text("Bootstrap and cut over") }
      OutlinedButton(
        onClick = onStopAll,
        enabled = !operation.inProgress,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
      ) { Text("Stop all services") }
      Button(
        onClick = onCleanup,
        enabled = !operation.inProgress,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError
        )
      ) { Text("Clean approved runtime residue") }
      if (operation.inProgress && operation.action in setOf("bootstrap", "stop_all", "cleanup")) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      if (!operation.inProgress && operation.action in setOf("bootstrap", "stop_all", "cleanup")) {
        Text(
          operation.message,
          style = MaterialTheme.typography.bodySmall,
          color = if (operation.status == DashboardOperationStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onErrorContainer
          }
        )
      }
    }
  }
}

@Composable
private fun ActivityRow(item: DashboardActivityItem) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
    verticalAlignment = Alignment.Top
  ) {
    Icon(
      imageVector = when (item.successful) {
        true -> Icons.Rounded.CheckCircle
        false -> Icons.Rounded.Clear
        null -> Icons.Rounded.Info
      },
      contentDescription = null,
      tint = when (item.successful) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
      },
      modifier = Modifier.size(20.dp)
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
      DateUtils.getRelativeTimeSpanString(
        item.recordedAtMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
      ).toString(),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun ConfirmationDialog(
  confirmation: Confirmation,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  val title: String
  val body: String
  val confirmLabel: String
  when (confirmation) {
    Confirmation.BOOTSTRAP -> {
      title = "Bootstrap the stack?"
      body = "This reinstalls and cuts over managed components. Settings and protected configuration stay in place. The retained rollback release can be redeployed; no storage recovery is expected."
      confirmLabel = "Bootstrap"
    }
    Confirmation.STOP_ALL -> {
      title = "Stop all services?"
      body = "This interrupts every managed runtime service. Settings and data stay in place, and Start all reverses the action. No files or storage are removed."
      confirmLabel = "Stop all"
    }
    Confirmation.CLEANUP -> {
      title = "Clean runtime residue?"
      body = "This removes only approved generated residue. Active and rollback releases, settings, secrets, and recovery state stay in place. Deleted residue cannot be restored; reclaimed storage is reported when cleanup finishes."
      confirmLabel = "Clean residue"
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
private fun statusContainerColor(level: DashboardHealthLevel): Color = when (level) {
  DashboardHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primaryContainer
  DashboardHealthLevel.DEGRADED, DashboardHealthLevel.STALE -> MaterialTheme.colorScheme.tertiaryContainer
  DashboardHealthLevel.FAILED -> MaterialTheme.colorScheme.errorContainer
  DashboardHealthLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun statusContentColor(level: DashboardHealthLevel): Color = when (level) {
  DashboardHealthLevel.HEALTHY -> MaterialTheme.colorScheme.onPrimaryContainer
  DashboardHealthLevel.DEGRADED, DashboardHealthLevel.STALE -> MaterialTheme.colorScheme.onTertiaryContainer
  DashboardHealthLevel.FAILED -> MaterialTheme.colorScheme.onErrorContainer
  DashboardHealthLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun healthTitle(level: DashboardHealthLevel): String = when (level) {
  DashboardHealthLevel.HEALTHY -> "Stack healthy"
  DashboardHealthLevel.DEGRADED -> "Stack needs attention"
  DashboardHealthLevel.FAILED -> "Stack has failures"
  DashboardHealthLevel.UNKNOWN -> "Health not checked"
  DashboardHealthLevel.STALE -> "Health information is stale"
}

private fun healthShortLabel(level: DashboardHealthLevel): String = when (level) {
  DashboardHealthLevel.HEALTHY -> "Healthy"
  DashboardHealthLevel.DEGRADED -> "Degraded"
  DashboardHealthLevel.FAILED -> "Failed"
  DashboardHealthLevel.UNKNOWN -> "Unknown"
  DashboardHealthLevel.STALE -> "Stale"
}

private fun healthSubtitle(level: DashboardHealthLevel): String = when (level) {
  DashboardHealthLevel.HEALTHY -> "All enabled services passed their latest checks."
  DashboardHealthLevel.DEGRADED -> "At least one service is unavailable or still settling."
  DashboardHealthLevel.FAILED -> "A service reported a failure that needs attention."
  DashboardHealthLevel.UNKNOWN -> "Run a full health check to populate every module."
  DashboardHealthLevel.STALE -> "Refresh health before relying on the status below."
}

private fun healthFreshness(generatedAtMillis: Long): String {
  if (generatedAtMillis <= 0L) return "No full health snapshot yet."
  return "Last full check ${DateUtils.getRelativeTimeSpanString(generatedAtMillis).toString().lowercase()}."
}

@Preview(name = "Pixel dashboard", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
  name = "Pixel dashboard dark, large text",
  showBackground = true,
  widthDp = 412,
  heightDp = 915,
  fontScale = 2f,
  uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun DashboardPreview() {
  PixelOrchestratorTheme {
    DashboardScreen(
      state = DashboardUiState(buildIdentity = "preview • a1b2c3d4 • 0.1.0"),
      onAction = {}
    )
  }
}
