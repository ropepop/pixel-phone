//! Pure supervisor state transitions. Service calls stay at their existing
//! Kotlin call sites; no effects, clock reads, persistence or worker lives here.
use crate::{Config, trim};
use serde_json::{Value, json};

fn object(text: &str) -> Result<Value, String> {
    let value: Value = serde_json::from_str(text).map_err(|_| "invalid supervisor JSON")?;
    if !value.is_object() {
        return Err("supervisor input must be an object".into());
    }
    Ok(value)
}
fn number(value: &Value, key: &str) -> Result<i64, String> {
    value[key]
        .as_i64()
        .ok_or_else(|| format!("supervisor requires integer {key}"))
}
fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> {
    value[key]
        .as_str()
        .ok_or_else(|| format!("supervisor requires string {key}"))
}
fn boolean(value: &Value, key: &str) -> Result<bool, String> {
    value[key]
        .as_bool()
        .ok_or_else(|| format!("supervisor requires boolean {key}"))
}
fn evidence<'a>(snapshot: &'a Value, key: &str) -> &'a str {
    snapshot["evidence"][key].as_str().unwrap_or("")
}
fn state(text: &str) -> Result<Value, String> {
    let value = object(text)?;
    if !value["services"].is_object()
        || !value["moduleState"].is_object()
        || !value["operationLog"].is_array()
    {
        return Err("invalid supervisor state collections".into());
    }
    Ok(value)
}
fn service(state: &Value, name: &str) -> Value {
    state["services"].get(name).cloned().unwrap_or_else(|| {
        json!({
            "status":"STOPPED", "restartCount":0, "lastFailureReason":"",
            "lastStartedEpochSeconds":0, "lastHealthyEpochSeconds":0
        })
    })
}
fn mark(
    state: &mut Value,
    name: &str,
    status: &str,
    failure: &str,
    restart: bool,
    now: i64,
) -> Result<(), String> {
    if !matches!(
        status,
        "STOPPED" | "STARTING" | "RUNNING" | "DEGRADED" | "CRASH_LOOP"
    ) {
        return Err("invalid supervisor service status".into());
    }
    let mut current = service(state, name);
    let count = number(&current, "restartCount")? as i32;
    if restart && status == "RUNNING" && string(&current, "status")? != "RUNNING" {
        current["restartCount"] = json!(count.wrapping_add(1));
    }
    current["status"] = json!(status);
    current["lastFailureReason"] = json!(failure);
    if status == "RUNNING" {
        current["lastStartedEpochSeconds"] = json!(now);
        current["lastHealthyEpochSeconds"] = json!(now);
    }
    state["services"][name] = current;
    Ok(())
}
fn event(state: &mut Value, component: &str, action: &str, success: bool, details: &str, now: i64) {
    let log = state["operationLog"].as_array_mut().unwrap();
    log.push(json!({"epochSeconds":now,"component":component,"action":action,"success":success,"details":details}));
    let excess = log.len().saturating_sub(100);
    log.drain(..excess);
}

pub fn transition(state_json: &str, change_json: &str, now: i64) -> Result<Value, String> {
    let mut state = state(state_json)?;
    let change = object(change_json)?;
    match string(&change, "kind")? {
        "component" => mark(
            &mut state,
            string(&change, "name")?,
            string(&change, "status")?,
            string(&change, "failure")?,
            boolean(&change, "countAsRestart")?,
            now,
        )?,
        "event" => event(
            &mut state,
            string(&change, "component")?,
            string(&change, "action")?,
            boolean(&change, "success")?,
            string(&change, "details")?,
            now,
        ),
        "supervisor" => {
            let status = string(&change, "status")?;
            if !matches!(
                status,
                "STOPPED" | "STARTING" | "RUNNING" | "DEGRADED" | "CRASH_LOOP"
            ) {
                return Err("invalid supervisor service status".into());
            }
            let mut current = service(&state, "supervisor");
            current["status"] = json!(status);
            if status == "RUNNING" {
                current["lastStartedEpochSeconds"] = json!(now);
                current["lastHealthyEpochSeconds"] = json!(now);
                state["lastSuccessfulBootEpochSeconds"] = json!(now);
            }
            state["services"]["supervisor"] = current;
        }
        "heartbeat" => state["supervisorLoopHeartbeatEpochSeconds"] = json!(now),
        "modules" => {
            let modules = change["snapshot"]["moduleHealth"]
                .as_object()
                .ok_or("invalid supervisor module health")?;
            for (name, module) in modules {
                state["moduleState"][name] = json!({"status":string(module,"status")?,"healthy":boolean(module,"healthy")?,"lastUpdatedEpochSeconds":now,"details":module["details"]});
            }
        }
        "management" => {
            let snapshot = &change["snapshot"];
            if evidence(snapshot, "management_enabled") == "true" {
                let healthy = boolean(snapshot, "managementHealthy")?;
                let reason = evidence(snapshot, "management_reason");
                let reason = if trim(reason).is_empty() {
                    "unknown"
                } else {
                    reason
                };
                let current = service(&state, "management");
                let status = string(&current, "status")?;
                if healthy && status != "RUNNING" {
                    mark(&mut state, "management", "RUNNING", "", false, now)?;
                    event(
                        &mut state,
                        "management",
                        "health_recovered",
                        true,
                        &format!("reason={reason}"),
                        now,
                    );
                } else if !healthy && status != "DEGRADED" {
                    mark(&mut state, "management", "DEGRADED", reason, false, now)?;
                    event(
                        &mut state,
                        "management",
                        "health_unhealthy",
                        false,
                        &format!("reason={reason}"),
                        now,
                    );
                }
            }
        }
        _ => return Err("unknown supervisor state transition".into()),
    }
    Ok(state)
}

fn normalized(value: &str) -> &str {
    let value = trim(value);
    if value.eq_ignore_ascii_case("none") {
        ""
    } else {
        value
    }
}

pub fn network(
    previous_json: &str,
    state_json: &str,
    snapshot_json: &str,
    config_json: &str,
    now: i64,
) -> Result<Value, String> {
    let previous = state(previous_json)?;
    let mut state = state(state_json)?;
    let snapshot = object(snapshot_json)?;
    let config = Config::parse(config_json)?;
    let fingerprint = normalized(evidence(&snapshot, "network_fingerprint"));
    let ip = normalized(evidence(&snapshot, "network_public_ipv4_candidate"));
    let prior_fingerprint = normalized(string(&state, "lastNetworkFingerprint")?);
    let prior_ip = normalized(string(&state, "lastObservedPublicIpv4")?);
    let fingerprint_changed = !fingerprint.is_empty()
        && !prior_fingerprint.is_empty()
        && fingerprint != prior_fingerprint;
    let ip_changed = !ip.is_empty() && !prior_ip.is_empty() && ip != prior_ip;
    let prior_transition = evidence(
        &previous["lastHealthSnapshot"],
        "direct_public_transitioning",
    ) == "true";
    if fingerprint_changed {
        state["networkConvergenceUntilEpochSeconds"] = json!(
            now.wrapping_add(
                config
                    .i("supervision.networkConvergenceWindowSeconds")?
                    .max(0)
            )
        );
        let transport = evidence(&snapshot, "network_active_transport");
        let transport = if trim(transport).is_empty() {
            "unknown"
        } else {
            transport
        };
        event(
            &mut state,
            "supervisor",
            "network_change",
            true,
            &format!(
                "transport={transport} public_ipv4={}",
                if ip.is_empty() { "unknown" } else { ip }
            ),
            now,
        );
    }
    let failed = evidence(&snapshot, "direct_public_path_healthy") == "false";
    let transitioning = evidence(&snapshot, "direct_public_transitioning") == "true";
    let prior_failure = number(&state, "lastDirectPublicFailureEpochSeconds")?;
    let details = format!(
        "published_ip={} current_ip={}",
        evidence(&snapshot, "ddns_published_ipv4"),
        evidence(&snapshot, "network_public_ipv4_candidate")
    );
    if failed {
        state["lastDirectPublicFailureEpochSeconds"] = json!(now);
        if prior_failure == 0 {
            event(
                &mut state,
                "remote",
                "direct_public_degraded",
                false,
                &details,
                now,
            );
        }
    } else if prior_failure != 0 {
        state["lastDirectPublicFailureEpochSeconds"] = json!(0);
        event(
            &mut state,
            "remote",
            "direct_public_recovered",
            true,
            &details,
            now,
        );
    }
    if !failed {
        if transitioning && !prior_transition {
            event(
                &mut state,
                "remote",
                "direct_public_transition",
                true,
                &format!(
                    "reason={} {details}",
                    evidence(&snapshot, "direct_public_transition_reason")
                ),
                now,
            );
        } else if !transitioning && prior_transition {
            event(
                &mut state,
                "remote",
                "direct_public_transition_recovered",
                true,
                &details,
                now,
            );
        }
    }
    if !fingerprint.is_empty() {
        state["lastNetworkFingerprint"] = json!(fingerprint);
    }
    if !ip.is_empty() {
        state["lastObservedPublicIpv4"] = json!(ip);
    }
    let convergence_active = now < number(&state, "networkConvergenceUntilEpochSeconds")?;
    Ok(
        json!({"state":state,"fingerprintChanged":fingerprint_changed,"publicIpv4Changed":ip_changed,"convergenceActive":convergence_active}),
    )
}
