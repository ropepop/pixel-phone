#!/usr/bin/env bash
set -euo pipefail

# Workflow-only strict single-item drain runner with adaptive continuation.
# Can be sourced for smoke tests; when executed directly it runs main().

normalize_bool() {
  local raw="${1:-false}"
  raw="$(echo "${raw}" | tr '[:upper:]' '[:lower:]')"
  case "${raw}" in
    true|1|yes|y|on) echo "true" ;;
    *) echo "false" ;;
  esac
}

safe_int() {
  local raw="${1:-0}"
  if [[ "${raw}" =~ ^-?[0-9]+$ ]]; then
    echo "${raw}"
  else
    echo "0"
  fi
}

max_int() {
  local left="$(safe_int "${1:-0}")"
  local right="$(safe_int "${2:-0}")"
  if [ "${left}" -ge "${right}" ]; then
    echo "${left}"
  else
    echo "${right}"
  fi
}

determine_runtime_tier() {
  local depth="$(safe_int "${1:-0}")"
  if [ "${depth}" -ge 8 ]; then
    echo "severe"
  elif [ "${depth}" -ge 3 ]; then
    echo "heavy"
  else
    echo "normal"
  fi
}

chain_origin_for_depth() {
  local depth="$(safe_int "${1:-0}")"
  case "${depth}" in
    0) echo "gen1" ;;
    1) echo "gen2" ;;
    2) echo "gen3" ;;
    3) echo "gen4" ;;
    *) echo "followup-depth-${depth}" ;;
  esac
}

normalize_chain_origin() {
  local raw="${1:-}"
  raw="$(echo "${raw}" | tr '[:upper:]' '[:lower:]')"
  case "${raw}" in
    ""|gen1|firstrun) echo "gen1" ;;
    gen2|secondrun) echo "gen2" ;;
    gen3|thirdrun) echo "gen3" ;;
    gen4|fourthrun) echo "gen4" ;;
    *) echo "${raw}" ;;
  esac
}

should_spawn_next_generation() {
  local backlog_after="$(safe_int "${1:-0}")"
  local chain_depth="$(safe_int "${2:-0}")"
  local max_dispatch_depth="$(safe_int "${3:-3}")"

  if [ "${backlog_after}" -gt 0 ] && [ "${chain_depth}" -lt "${max_dispatch_depth}" ]; then
    echo "true"
  else
    echo "false"
  fi
}

effective_rate_limit_only() {
  local rate_limit_only_in="$(normalize_bool "${1:-false}")"
  local succeeded_count="$(safe_int "${2:-0}")"
  local rate_limited_count="$(safe_int "${3:-0}")"

  if [ "${rate_limit_only_in}" != "true" ] && [ "${succeeded_count}" -eq 0 ] && [ "${rate_limited_count}" -gt 0 ]; then
    echo "true"
    return 0
  fi

  echo "${rate_limit_only_in}"
}

single_item_contract_ok() {
  local attempted_count="$(safe_int "${1:-0}")"
  local succeeded_count="$(safe_int "${2:-0}")"

  if [ "${attempted_count}" -gt 1 ] || [ "${succeeded_count}" -gt 1 ]; then
    echo "false"
  else
    echo "true"
  fi
}

retry_cap_reached() {
  local retry_count="$(safe_int "${1:-0}")"
  local max_retries="$(safe_int "${2:-0}")"

  if [ "${retry_count}" -gt "${max_retries}" ]; then
    echo "true"
  else
    echo "false"
  fi
}

compute_exponential_backoff_sec() {
  local base_sec="$(safe_int "${1:-2}")"
  local cap_sec="$(safe_int "${2:-300}")"
  local jitter_pct="$(safe_int "${3:-25}")"
  local retry_count="$(safe_int "${4:-1}")"

  if [ "${base_sec}" -lt 1 ]; then
    base_sec=1
  fi
  if [ "${cap_sec}" -lt "${base_sec}" ]; then
    cap_sec="${base_sec}"
  fi
  if [ "${jitter_pct}" -lt 0 ]; then
    jitter_pct=0
  fi
  if [ "${retry_count}" -lt 1 ]; then
    retry_count=1
  fi

  local exp_sec="${base_sec}"
  local i=1
  while [ "${i}" -lt "${retry_count}" ]; do
    if [ "${exp_sec}" -ge "${cap_sec}" ]; then
      exp_sec="${cap_sec}"
      break
    fi
    exp_sec=$((exp_sec * 2))
    if [ "${exp_sec}" -gt "${cap_sec}" ]; then
      exp_sec="${cap_sec}"
      break
    fi
    i=$((i + 1))
  done

  local jitter_range=$((exp_sec * jitter_pct / 100))
  local jitter_delta=0
  if [ "${jitter_range}" -gt 0 ]; then
    jitter_delta=$((RANDOM % (jitter_range * 2 + 1) - jitter_range))
  fi

  local backoff_sec=$((exp_sec + jitter_delta))
  if [ "${backoff_sec}" -lt 1 ]; then
    backoff_sec=1
  fi
  if [ "${backoff_sec}" -gt "${cap_sec}" ]; then
    backoff_sec="${cap_sec}"
  fi

  echo "${backoff_sec}"
}

extract_iteration_metrics() {
  local file="${1}"
  jq -r '
    def n(x): (x | tonumber? // 0);
    def b(x): if x == true then "true" elif x == false then "false" else "unset" end;
    [
      n(.data.before.pendingCount // .before.pendingCount // 0),
      n(.data.before.dispatchedCount // .before.dispatchedCount // 0),
      n(.data.after.pendingCount // .after.pendingCount // 0),
      n(.data.after.dispatchedCount // .after.dispatchedCount // 0),
      n(.data.attemptedCount // .attemptedCount // 0),
      n(.data.succeededCount // .succeededCount // 0),
      n(.data.rateLimitedCount // .rateLimitedCount // 0),
      b(
        if ((.data | type) == "object" and (.data | has("rateLimitOnly"))) then
          .data.rateLimitOnly
        elif has("rateLimitOnly") then
          .rateLimitOnly
        else
          null
        end
      ),
      (.data.retryAfterSec // .retryAfterSec // "")
    ] | @tsv
  ' "${file}" 2>/dev/null || printf "0\t0\t0\t0\t0\t0\t0\tunset\t\n"
}

# Returns one of:
# no_backlog_remaining, max_chain_depth_reached, single_item_contract_violation,
# item_succeeded_continue, rate_limited_only_no_success, no_progress.
determine_iteration_decision() {
  local backlog_after="$(safe_int "${1:-0}")"
  local attempted_count="$(safe_int "${2:-0}")"
  local succeeded_count="$(safe_int "${3:-0}")"
  local rate_limit_only="$(normalize_bool "${4:-false}")"
  local chain_depth="$(safe_int "${5:-0}")"
  local max_chain_depth="$(safe_int "${6:-500}")"

  if [ "$(single_item_contract_ok "${attempted_count}" "${succeeded_count}")" != "true" ]; then
    echo "single_item_contract_violation"
    return 0
  fi

  if [ "${backlog_after}" -le 0 ]; then
    echo "no_backlog_remaining"
    return 0
  fi

  if [ "${chain_depth}" -ge "${max_chain_depth}" ]; then
    echo "max_chain_depth_reached"
    return 0
  fi

  if [ "${succeeded_count}" -eq 1 ]; then
    echo "item_succeeded_continue"
    return 0
  fi

  if [ "${rate_limit_only}" = "true" ]; then
    echo "rate_limited_only_no_success"
    return 0
  fi

  echo "no_progress"
}

emit_summary() {
  local file="${GITHUB_STEP_SUMMARY:-}"
  local content="${1:-}"
  if [ -n "${file}" ]; then
    printf "%s\n" "${content}" >> "${file}"
  fi
}

run_self_test() {
  # Generation mapping and legacy normalization.
  [ "$(chain_origin_for_depth 0)" = "gen1" ]
  [ "$(chain_origin_for_depth 1)" = "gen2" ]
  [ "$(chain_origin_for_depth 2)" = "gen3" ]
  [ "$(chain_origin_for_depth 3)" = "gen4" ]
  [ "$(normalize_chain_origin firstrun)" = "gen1" ]
  [ "$(normalize_chain_origin secondrun)" = "gen2" ]
  [ "$(normalize_chain_origin thirdrun)" = "gen3" ]
  [ "$(normalize_chain_origin fourthrun)" = "gen4" ]
  [ "$(normalize_chain_origin gen3)" = "gen3" ]

  # Backlog-driven generation spawn eligibility.
  [ "$(should_spawn_next_generation 10 0 3)" = "true" ]
  [ "$(should_spawn_next_generation 4 2 3)" = "true" ]
  [ "$(should_spawn_next_generation 0 0 3)" = "false" ]
  [ "$(should_spawn_next_generation 8 3 3)" = "false" ]

  # Contract validation.
  [ "$(single_item_contract_ok 1 1)" = "true" ]
  [ "$(single_item_contract_ok 2 1)" = "false" ]
  [ "$(single_item_contract_ok 1 2)" = "false" ]

  # Rate-limit-only detection.
  [ "$(effective_rate_limit_only false 0 5)" = "true" ]
  [ "$(effective_rate_limit_only false 1 1)" = "false" ]
  [ "$(effective_rate_limit_only true 0 0)" = "true" ]

  # Exponential backoff deterministic checks (no jitter).
  [ "$(compute_exponential_backoff_sec 2 300 0 1)" -eq 2 ]
  [ "$(compute_exponential_backoff_sec 2 300 0 2)" -eq 4 ]
  [ "$(compute_exponential_backoff_sec 2 300 0 3)" -eq 8 ]
  [ "$(compute_exponential_backoff_sec 2 300 0 10)" -eq 300 ]

  # Retry-cap logic.
  [ "$(retry_cap_reached 7 7)" = "false" ]
  [ "$(retry_cap_reached 8 7)" = "true" ]

  # Success-gated progression and stop conditions.
  [ "$(determine_iteration_decision 0 0 0 false 0 500)" = "no_backlog_remaining" ]
  [ "$(determine_iteration_decision 0 2 0 false 0 500)" = "single_item_contract_violation" ]
  [ "$(determine_iteration_decision 20 2 1 false 0 500)" = "single_item_contract_violation" ]
  [ "$(determine_iteration_decision 20 1 1 false 0 500)" = "item_succeeded_continue" ]
  [ "$(determine_iteration_decision 20 1 0 true 0 500)" = "rate_limited_only_no_success" ]
  [ "$(determine_iteration_decision 20 1 0 false 0 500)" = "no_progress" ]
  [ "$(determine_iteration_decision 20 1 1 false 500 500)" = "max_chain_depth_reached" ]

  echo "drain_runner self-test passed"
}

main() {
  if [ "${DRAIN_RUNNER_SELF_TEST:-0}" = "1" ]; then
    run_self_test
    return 0
  fi

  : "${STALLED_RUNNER_BASE_URL:?Missing STALLED_RUNNER_BASE_URL}"
  : "${CRON_SECRET:?Missing CRON_SECRET}"
  : "${GITHUB_TOKEN:?Missing GITHUB_TOKEN}"
  : "${GITHUB_REPOSITORY:?Missing GITHUB_REPOSITORY}"
  : "${GITHUB_REF_NAME:?Missing GITHUB_REF_NAME}"
  : "${GITHUB_RUN_ID:?Missing GITHUB_RUN_ID}"
  : "${GITHUB_EVENT_NAME:?Missing GITHUB_EVENT_NAME}"
  export PIXEL_RUN_ID="${PIXEL_RUN_ID:-${GITHUB_RUN_ID}}"

  local chain_depth="$(safe_int "${CHAIN_DEPTH_INPUT:-0}")"
  local chain_origin_input="${CHAIN_ORIGIN_INPUT:-}"
  local chain_origin_default
  chain_origin_default="$(chain_origin_for_depth "${chain_depth}")"
  local chain_origin
  chain_origin="$(normalize_chain_origin "${chain_origin_input:-${chain_origin_default}}")"
  local run_budget_minutes="$(safe_int "${RUN_BUDGET_MINUTES_INPUT:-100}")"
  local max_request_timeout_sec="$(safe_int "${MAX_REQUEST_TIMEOUT_SEC_INPUT:-1800}")"
  local max_iterations="$(safe_int "${MAX_ITERATIONS_INPUT:-60}")"
  local min_iterations_before_chain="$(safe_int "${MIN_ITERATIONS_BEFORE_CHAIN_INPUT:-2}")"
  local max_chain_depth="$(safe_int "${MAX_CHAIN_DEPTH_INPUT:-500}")"
  local max_dispatch_depth="$(safe_int "${MAX_DISPATCH_DEPTH_INPUT:-3}")"

  local backoff_base_sec="$(safe_int "${BACKOFF_BASE_SEC_INPUT:-2}")"
  local backoff_cap_sec="$(safe_int "${BACKOFF_CAP_SEC_INPUT:-300}")"
  local backoff_max_retries="$(safe_int "${BACKOFF_MAX_RETRIES_INPUT:-7}")"
  local backoff_jitter_pct="$(safe_int "${BACKOFF_JITTER_PCT_INPUT:-25}")"

  local run_budget_sec=$((run_budget_minutes * 60))
  local min_request_timeout_sec=120
  local target_request_timeout_sec=900
  local dispatch_buffer_sec=90
  if [ "${run_budget_sec}" -lt 600 ]; then
    run_budget_sec=600
  fi
  if [ "${max_request_timeout_sec}" -lt "${min_request_timeout_sec}" ]; then
    max_request_timeout_sec="${min_request_timeout_sec}"
  fi
  if [ "${max_iterations}" -lt 1 ]; then
    max_iterations=1
  fi
  if [ "${min_iterations_before_chain}" -lt 1 ]; then
    min_iterations_before_chain=1
  fi
  if [ "${max_chain_depth}" -lt 1 ]; then
    max_chain_depth=1
  fi
  if [ "${max_chain_depth}" -gt 1000 ]; then
    max_chain_depth=1000
  fi
  if [ "${max_dispatch_depth}" -lt 0 ]; then
    max_dispatch_depth=0
  fi
  if [ "${max_dispatch_depth}" -gt "${max_chain_depth}" ]; then
    max_dispatch_depth="${max_chain_depth}"
  fi

  if [ "${backoff_base_sec}" -lt 1 ]; then
    backoff_base_sec=1
  fi
  if [ "${backoff_cap_sec}" -lt "${backoff_base_sec}" ]; then
    backoff_cap_sec="${backoff_base_sec}"
  fi
  if [ "${backoff_max_retries}" -lt 0 ]; then
    backoff_max_retries=0
  fi
  if [ "${backoff_jitter_pct}" -lt 0 ]; then
    backoff_jitter_pct=0
  fi

  local run_start_epoch
  run_start_epoch="$(date +%s)"

  local drain_url="${STALLED_RUNNER_BASE_URL%/}/api/internal/pipeline/operations/actions/drain"
  local decision_code=""
  local decision_reason=""
  local chain_action="none"
  local should_dispatch="false"
  local iteration_count=0
  local exit_code=0

  local final_before_backlog=0
  local final_after_backlog=0
  local final_before_pending=0
  local final_before_dispatched=0
  local final_after_pending=0
  local final_after_dispatched=0
  local final_attempted_count=0
  local final_succeeded_count=0
  local final_rate_limited_count=0
  local final_rate_limit_only="false"
  local final_retry_after_sec="0"
  local rate_limit_retry_count=0

  while [ "${iteration_count}" -lt "${max_iterations}" ]; do
    iteration_count=$((iteration_count + 1))

    local now_epoch elapsed_sec remaining_budget_sec available_for_request_sec
    now_epoch="$(date +%s)"
    elapsed_sec=$((now_epoch - run_start_epoch))
    remaining_budget_sec=$((run_budget_sec - elapsed_sec))
    available_for_request_sec=$((remaining_budget_sec - dispatch_buffer_sec))

    if [ "${available_for_request_sec}" -lt 30 ]; then
      decision_code="run_budget_exhausted"
      decision_reason="Run budget exhausted before next safe iteration"
      break
    fi

    local request_timeout_sec="${target_request_timeout_sec}"
    if [ "${request_timeout_sec}" -gt "${max_request_timeout_sec}" ]; then
      request_timeout_sec="${max_request_timeout_sec}"
    fi
    if [ "${request_timeout_sec}" -gt "${available_for_request_sec}" ]; then
      request_timeout_sec="${available_for_request_sec}"
    fi
    if [ "${request_timeout_sec}" -lt "${min_request_timeout_sec}" ]; then
      request_timeout_sec="${available_for_request_sec}"
    fi
    if [ "${request_timeout_sec}" -lt 30 ]; then
      decision_code="run_budget_exhausted"
      decision_reason="Not enough budget for a useful request window"
      break
    fi

    local runtime_tier
    runtime_tier="$(determine_runtime_tier "${chain_depth}")"

    local response_file status_code
    response_file="$(mktemp)"
    status_code="$(
      curl \
        --silent \
        --show-error \
        --connect-timeout 10 \
        --max-time "${request_timeout_sec}" \
        --output "${response_file}" \
        --write-out '%{http_code}' \
        --request POST \
        --header "Authorization: Bearer ${CRON_SECRET}" \
        --header "Content-Type: application/json" \
        --header "X-Cron-Source: task-executor" \
        --header "X-Cron-Run-Id: ${GITHUB_RUN_ID}" \
        --header "X-Cron-Event: ${GITHUB_EVENT_NAME}" \
        --header "X-Cron-Chain-Depth: ${chain_depth}" \
        --header "X-Cron-Runtime-Tier: ${runtime_tier}" \
        --header "X-Cron-Target-Runtime-Sec: ${request_timeout_sec}" \
        --header "X-Cron-Processing-Mode: single-item-strict" \
        --header "X-Cron-Max-Items: 1" \
        --header "X-Cron-Backoff-Strategy: exponential-jitter" \
        "${drain_url}"
    )"

    echo "Iteration ${iteration_count} status=${status_code} requestTimeoutSec=${request_timeout_sec} runtimeTier=${runtime_tier}"
    cat "${response_file}"

    if [ "${status_code}" -lt 200 ] || [ "${status_code}" -ge 300 ]; then
      rm -f "${response_file}" >/dev/null 2>&1 || true
      echo "Operation queue drain failed"
      exit 1
    fi

    local metrics_line
    metrics_line="$(extract_iteration_metrics "${response_file}")"
    rm -f "${response_file}" >/dev/null 2>&1 || true

    local before_pending before_dispatched after_pending after_dispatched
    local attempted_count succeeded_count rate_limited_count rate_limit_only retry_after_sec
    IFS=$'\t' read -r \
      before_pending \
      before_dispatched \
      after_pending \
      after_dispatched \
      attempted_count \
      succeeded_count \
      rate_limited_count \
      rate_limit_only \
      retry_after_sec <<< "${metrics_line}"

    before_pending="$(safe_int "${before_pending}")"
    before_dispatched="$(safe_int "${before_dispatched}")"
    after_pending="$(safe_int "${after_pending}")"
    after_dispatched="$(safe_int "${after_dispatched}")"
    attempted_count="$(safe_int "${attempted_count}")"
    succeeded_count="$(safe_int "${succeeded_count}")"
    rate_limited_count="$(safe_int "${rate_limited_count}")"
    retry_after_sec="$(safe_int "${retry_after_sec}")"
    rate_limit_only="$(effective_rate_limit_only "${rate_limit_only}" "${succeeded_count}" "${rate_limited_count}")"

    local backlog_before backlog_after
    backlog_before=$((before_pending + before_dispatched))
    backlog_after=$((after_pending + after_dispatched))

    final_before_pending="${before_pending}"
    final_before_dispatched="${before_dispatched}"
    final_after_pending="${after_pending}"
    final_after_dispatched="${after_dispatched}"
    final_before_backlog="${backlog_before}"
    final_after_backlog="${backlog_after}"
    final_attempted_count="${attempted_count}"
    final_succeeded_count="${succeeded_count}"
    final_rate_limited_count="${rate_limited_count}"
    final_rate_limit_only="${rate_limit_only}"
    final_retry_after_sec="${retry_after_sec}"

    echo "Iteration ${iteration_count} backlogBefore=${backlog_before} backlogAfter=${backlog_after} attempted=${attempted_count} succeeded=${succeeded_count} rateLimited=${rate_limited_count} rateLimitOnly=${rate_limit_only} retryAfterSec=${retry_after_sec}"

    decision_code="$(
      determine_iteration_decision \
        "${backlog_after}" \
        "${attempted_count}" \
        "${succeeded_count}" \
        "${rate_limit_only}" \
        "${chain_depth}" \
        "${max_chain_depth}"
    )"

    case "${decision_code}" in
      no_backlog_remaining)
        decision_reason="No backlog remains after drain"
        break
        ;;
      max_chain_depth_reached)
        decision_reason="Maximum chain depth reached (${chain_depth}/${max_chain_depth})"
        break
        ;;
      single_item_contract_violation)
        decision_reason="Single-item contract violated (attempted=${attempted_count}, succeeded=${succeeded_count}); endpoint still processing batches, stopping safely"
        exit_code=0
        break
        ;;
      item_succeeded_continue)
        rate_limit_retry_count=0
        decision_reason="Current item executed successfully; evaluating next item"

        local now_success remaining_success
        now_success="$(date +%s)"
        remaining_success=$((run_budget_sec - (now_success - run_start_epoch)))
        if [ "${remaining_success}" -le $((dispatch_buffer_sec + min_request_timeout_sec)) ]; then
          should_dispatch="true"
          chain_action="self_dispatch"
          decision_code="run_budget_exhausted_with_progress"
          decision_reason="Run budget boundary reached after successful item execution"
          break
        fi

        decision_code="item_succeeded_continue"
        continue
        ;;
      rate_limited_only_no_success)
        rate_limit_retry_count=$((rate_limit_retry_count + 1))

        if [ "$(retry_cap_reached "${rate_limit_retry_count}" "${backoff_max_retries}")" = "true" ]; then
          decision_code="rate_limit_retry_cap_reached"
          decision_reason="Rate-limit retry cap reached (${rate_limit_retry_count}/${backoff_max_retries})"
          break
        fi

        local computed_backoff_sec sleep_sec
        computed_backoff_sec="$(compute_exponential_backoff_sec "${backoff_base_sec}" "${backoff_cap_sec}" "${backoff_jitter_pct}" "${rate_limit_retry_count}")"
        sleep_sec="$(max_int "${computed_backoff_sec}" "${retry_after_sec}")"

        local now_rate remaining_rate minimum_needed
        now_rate="$(date +%s)"
        remaining_rate=$((run_budget_sec - (now_rate - run_start_epoch)))
        minimum_needed=$((sleep_sec + dispatch_buffer_sec + 30))
        if [ "${remaining_rate}" -le "${minimum_needed}" ]; then
          decision_code="rate_limit_budget_exhausted"
          decision_reason="Insufficient budget for backoff retry (remaining=${remaining_rate}s, need>${minimum_needed}s)"
          break
        fi

        if [ "${iteration_count}" -ge "${max_iterations}" ]; then
          decision_code="rate_limit_retry_cap_reached"
          decision_reason="Rate-limited with no iterations left for another retry"
          break
        fi

        echo "Rate-limited-only iteration; retry=${rate_limit_retry_count}/${backoff_max_retries} retryAfterSec=${retry_after_sec} computedBackoffSec=${computed_backoff_sec} sleepSec=${sleep_sec}"
        sleep "${sleep_sec}"
        continue
        ;;
      no_progress)
        decision_reason="No item succeeded and no rate-limit retry path available"
        break
        ;;
      *)
        decision_reason="Stopped by safety condition"
        break
        ;;
    esac
  done

  if [ -z "${decision_reason}" ]; then
    if [ "${decision_code}" = "run_budget_exhausted" ]; then
      decision_reason="Run budget exhausted before another iteration"
    elif [ "${decision_code}" = "item_succeeded_continue" ]; then
      decision_reason="Iteration ended after successful item execution"
    else
      if [ -z "${decision_code}" ]; then
        decision_code="iteration_or_budget_guard_stop"
      fi
      decision_reason="Stopped after reaching iteration or budget guard"
    fi
  fi

  local spawn_due_backlog="false"
  if [ "$(should_spawn_next_generation "${final_after_backlog}" "${chain_depth}" "${max_dispatch_depth}")" = "true" ]; then
    should_dispatch="true"
    chain_action="self_dispatch"
    spawn_due_backlog="true"
    decision_reason="${decision_reason}; backlog remains so next generation will be spawned"
  else
    should_dispatch="false"
    chain_action="none"
    if [ "${final_after_backlog}" -le 0 ]; then
      decision_code="no_backlog_remaining"
      decision_reason="${decision_reason}; backlog cleared or empty"
    elif [ "${chain_depth}" -ge "${max_chain_depth}" ]; then
      decision_code="max_chain_depth_reached"
      decision_reason="Backlog remains but maximum chain depth reached (${chain_depth}/${max_chain_depth})"
    elif [ "${chain_depth}" -ge "${max_dispatch_depth}" ]; then
      decision_code="dispatch_depth_cap_reached"
      decision_reason="Backlog remains but dispatch depth cap reached (${chain_depth}/${max_dispatch_depth})"
    fi
  fi

  local current_generation next_generation
  current_generation="$(chain_origin_for_depth "${chain_depth}")"
  if [ "${should_dispatch}" = "true" ]; then
    next_generation="$(chain_origin_for_depth "$((chain_depth + 1))")"
  else
    next_generation="none"
  fi

  local run_end_epoch elapsed_total_sec remaining_budget_sec
  run_end_epoch="$(date +%s)"
  elapsed_total_sec=$((run_end_epoch - run_start_epoch))
  remaining_budget_sec=$((run_budget_sec - elapsed_total_sec))
  if [ "${remaining_budget_sec}" -lt 0 ]; then
    remaining_budget_sec=0
  fi

  echo "Backlog before: ${final_before_backlog} (pending=${final_before_pending}, dispatched=${final_before_dispatched})"
  echo "Backlog after: ${final_after_backlog} (pending=${final_after_pending}, dispatched=${final_after_dispatched})"
  echo "Remaining backlog: ${final_after_backlog}"
  echo "Iterations executed: ${iteration_count}"
  echo "Attempted count: ${final_attempted_count}"
  echo "Succeeded count: ${final_succeeded_count}"
  echo "Rate-limited count: ${final_rate_limited_count}"
  echo "Rate-limit-only: ${final_rate_limit_only}"
  echo "Retry-after seconds: ${final_retry_after_sec}"
  echo "Rate-limit retries used: ${rate_limit_retry_count}"
  echo "Backoff config: base=${backoff_base_sec}s cap=${backoff_cap_sec}s jitterPct=${backoff_jitter_pct} maxRetries=${backoff_max_retries}"
  echo "Chain context: depth=${chain_depth}, origin=${chain_origin}"
  echo "Current generation: ${current_generation}"
  echo "Spawn due backlog: ${spawn_due_backlog}"
  echo "Next generation: ${next_generation}"
  echo "Chain dispatch depth cap: ${max_dispatch_depth}"
  echo "Chain control input (legacy): minIterationsBeforeChain=${min_iterations_before_chain}"
  echo "Decision code: ${decision_code}"
  echo "Decision reason: ${decision_reason}"
  echo "Iteration count: ${iteration_count}/${max_iterations}"
  echo "Run budget sec: ${run_budget_sec}"
  echo "Remaining budget sec: ${remaining_budget_sec}"
  echo "Chain action: ${chain_action}"

  emit_summary "### Operation Queue Drain Summary"
  emit_summary ""
  emit_summary "- Backlog before: \`${final_before_backlog}\` (pending=\`${final_before_pending}\`, dispatched=\`${final_before_dispatched}\`)"
  emit_summary "- Backlog after: \`${final_after_backlog}\` (pending=\`${final_after_pending}\`, dispatched=\`${final_after_dispatched}\`)"
  emit_summary "- Remaining backlog: \`${final_after_backlog}\`"
  emit_summary "- Iterations executed: \`${iteration_count}\`"
  emit_summary "- Attempted count: \`${final_attempted_count}\`"
  emit_summary "- Succeeded count: \`${final_succeeded_count}\`"
  emit_summary "- Rate-limited count: \`${final_rate_limited_count}\`"
  emit_summary "- Rate-limit-only: \`${final_rate_limit_only}\`"
  emit_summary "- Retry-after seconds: \`${final_retry_after_sec}\`"
  emit_summary "- Rate-limit retries used: \`${rate_limit_retry_count}\`"
  emit_summary "- Backoff config: base=\`${backoff_base_sec}\` cap=\`${backoff_cap_sec}\` jitterPct=\`${backoff_jitter_pct}\` maxRetries=\`${backoff_max_retries}\`"
  emit_summary "- Chain depth: \`${chain_depth}\` (max \`${max_chain_depth}\`)"
  emit_summary "- Chain origin: \`${chain_origin}\`"
  emit_summary "- Current generation: \`${current_generation}\`"
  emit_summary "- Spawn due backlog: \`${spawn_due_backlog}\`"
  emit_summary "- Next generation: \`${next_generation}\`"
  emit_summary "- Chain dispatch depth cap: \`${max_dispatch_depth}\`"
  emit_summary "- Chain control input (legacy): minIterationsBeforeChain=\`${min_iterations_before_chain}\`"
  emit_summary "- Decision code: \`${decision_code}\`"
  emit_summary "- Decision reason: ${decision_reason}"
  emit_summary "- Iteration count: \`${iteration_count}\` / \`${max_iterations}\`"
  emit_summary "- Run budget sec: \`${run_budget_sec}\`"
  emit_summary "- Remaining budget sec: \`${remaining_budget_sec}\`"
  emit_summary "- Chain action: \`${chain_action}\`"

  if [ "${should_dispatch}" != "true" ]; then
    return "${exit_code}"
  fi

  local next_chain_depth
  next_chain_depth=$((chain_depth + 1))
  local next_chain_origin
  next_chain_origin="$(chain_origin_for_depth "${next_chain_depth}")"

  local dispatch_url dispatch_response_file dispatch_payload dispatch_status
  dispatch_url="https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/workflows/stalled-runner-cron.yml/dispatches"
  dispatch_response_file="$(mktemp)"
  dispatch_payload="$(
    jq -n \
      --arg ref "${GITHUB_REF_NAME}" \
      --arg chain_depth "${next_chain_depth}" \
      --arg chain_origin "${next_chain_origin}" \
      --arg run_budget_minutes "${run_budget_minutes}" \
      --arg max_request_timeout_sec "${max_request_timeout_sec}" \
      --arg max_iterations "${max_iterations}" \
      --arg min_iterations_before_chain "${min_iterations_before_chain}" \
      --arg max_chain_depth "${max_chain_depth}" \
      --arg max_dispatch_depth "${max_dispatch_depth}" \
      --arg backoff_base_sec "${backoff_base_sec}" \
      --arg backoff_cap_sec "${backoff_cap_sec}" \
      --arg backoff_max_retries "${backoff_max_retries}" \
      --arg backoff_jitter_pct "${backoff_jitter_pct}" \
      '{
        ref:$ref,
        inputs:{
          chain_depth:$chain_depth,
          chain_origin:$chain_origin,
          run_budget_minutes:$run_budget_minutes,
          max_request_timeout_sec:$max_request_timeout_sec,
          max_iterations:$max_iterations,
          min_iterations_before_chain:$min_iterations_before_chain,
          max_chain_depth:$max_chain_depth,
          max_dispatch_depth:$max_dispatch_depth,
          backoff_base_sec:$backoff_base_sec,
          backoff_cap_sec:$backoff_cap_sec,
          backoff_max_retries:$backoff_max_retries,
          backoff_jitter_pct:$backoff_jitter_pct
        }
      }'
  )"

  dispatch_status="$(
    curl \
      --silent \
      --show-error \
      --connect-timeout 10 \
      --max-time 60 \
      --output "${dispatch_response_file}" \
      --write-out '%{http_code}' \
      --request POST \
      --header "Accept: application/vnd.github+json" \
      --header "Authorization: Bearer ${GITHUB_TOKEN}" \
      --header "X-GitHub-Api-Version: 2022-11-28" \
      --header "Content-Type: application/json" \
      --data "${dispatch_payload}" \
      "${dispatch_url}"
  )"

  echo "Self-dispatch status: ${dispatch_status}"
  if [ -s "${dispatch_response_file}" ]; then
    cat "${dispatch_response_file}"
  fi

  if [ "${dispatch_status}" -lt 200 ] || [ "${dispatch_status}" -ge 300 ]; then
    rm -f "${dispatch_response_file}" >/dev/null 2>&1 || true
    emit_summary "- Self-dispatch status: \`${dispatch_status}\`"
    emit_summary "- Self-dispatch result: failed"
    echo "Self-dispatch failed"
    exit 1
  fi

  rm -f "${dispatch_response_file}" >/dev/null 2>&1 || true
  emit_summary "- Self-dispatch status: \`${dispatch_status}\`"
  emit_summary "- Self-dispatch result: queued next worker with \`chain_depth=${next_chain_depth}\`, \`chain_origin=${next_chain_origin}\`"
  return "${exit_code}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
