//! Redeploy acceptance policy. Android owns probes, clocks, delays and mutations.
use serde_json::{Value, json};

fn items(value: &Value) -> &[Value] {
    value.as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn number(value: &Value, key: &str) -> i64 {
    value[key].as_i64().unwrap_or(0)
}
fn contains(values: &Value, component: &str) -> bool {
    items(values)
        .iter()
        .any(|value| value.as_str() == Some(component))
}
fn healthy(snapshot: &Value, component: &str) -> bool {
    snapshot["moduleHealth"][component]["healthy"]
        .as_bool()
        .unwrap_or_else(|| {
            let field = match component {
                "ssh" => "sshHealthy",
                "vpn" => "vpnHealthy",
                "management" => "managementHealthy",
                _ => return false,
            };
            snapshot[field].as_bool().unwrap_or(false)
        })
}
fn ready(snapshot: &Value, component: &str, disabled: &Value) -> bool {
    healthy(snapshot, component)
        || (contains(disabled, component)
            && snapshot["moduleHealth"][component]["status"].as_str() == Some("disabled"))
}
fn neighbors(args: &Value) -> Vec<Value> {
    items(&args["supported"])
        .iter()
        .filter(|component| {
            let name = component.as_str().unwrap_or("");
            !contains(&args["targets"], name) && healthy(&args["preMutation"], name)
        })
        .cloned()
        .collect()
}
fn spec(component: &str) -> Result<Value, String> {
    let (runtime, release, action, gates, targets, cleanup, probe) = match component {
        "dns" | "ssh" | "vpn" => (
            component,
            true,
            "restart_component",
            vec![component],
            vec![component],
            "",
            "",
        ),
        "ddns" => (
            component,
            false,
            "sync_ddns",
            vec![component],
            vec![component],
            "",
            "",
        ),
        "ticket_screen" => (
            component,
            false,
            "restart_component",
            vec![],
            vec![component],
            "",
            "",
        ),
        "remote" => (
            "dns",
            true,
            "restart_component",
            vec!["dns", "remote"],
            vec!["dns", "remote"],
            "",
            "",
        ),
        "train_bot" => (
            component,
            true,
            "restart_component",
            vec![component],
            vec![component],
            "sh /data/local/pixel-stack/bin/pixel-train-stop.sh",
            "train_bot",
        ),
        "satiksme_bot" => (
            component,
            true,
            "restart_component",
            vec![component],
            vec![component],
            "sh /data/local/pixel-stack/bin/pixel-satiksme-stop.sh",
            "satiksme_bot",
        ),
        "site_notifier" => (
            component,
            true,
            "restart_component",
            vec![component],
            vec![component],
            "sh /data/local/pixel-stack/bin/pixel-notifier-stop.sh",
            "site_notifier",
        ),
        "subscription_bot" => (
            component,
            true,
            "restart_component",
            vec![component],
            vec![component],
            "sh /data/local/pixel-stack/bin/pixel-subscription-stop.sh",
            "subscription_bot",
        ),
        _ => return Err(format!("Unsupported redeploy component: {component}")),
    };
    let quiescent = !probe.is_empty();
    Ok(
        json!({"requestedComponent":component,"runtimeConfigComponent":runtime,"runtimeAssetComponent":runtime,
        "releaseManifestComponent":if release {Some(runtime)} else {None},"releaseInstallComponent":if release {Some(runtime)} else {None},
        "runtimeAction":action,"runtimeActionComponent":runtime,"stopComponent":runtime,"requiresQuiescentInstall":quiescent,
        "quiescenceProbeAdapter":probe,"quiescenceProbeScript":"","staleCleanupCommand":cleanup,
        "rollbackStrategy":if quiescent {"PREVIOUS_CURRENT_RELEASE"} else {"NONE"},"rollbackComponent":component,
        "retryBudget":if quiescent {1} else {0},"healthGateComponents":gates,"targetComponents":targets,"requiresReleaseManifest":release}),
    )
}
fn joined(value: &Value) -> String {
    items(value)
        .iter()
        .map(|v| v.as_str().unwrap_or(""))
        .collect::<Vec<_>>()
        .join(", ")
}
fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let args: Value = serde_json::from_str(payload).map_err(|_| "invalid redeploy policy JSON")?;
    let component = args["component"].as_str().unwrap_or("");
    Ok(match operation {
        "spec" => spec(component)?,
        "rollback_code" => {
            let gate = args["gateHealthy"].as_bool().unwrap_or(false);
            let regressed = !items(&args["regressed"]).is_empty();
            let stable = args["stable"].as_bool().unwrap_or(false);
            json!(if !gate && regressed {
                "post_deploy_failed_rolled_back"
            } else if !gate {
                "health_gate_failed_rolled_back"
            } else if !stable && !regressed {
                "stabilization_window_failed_rolled_back"
            } else {
                "neighbor_regression_rolled_back"
            })
        }
        "issues" => {
            let gate = args["gateHealthy"].as_bool().unwrap_or(false);
            let mut issues = Vec::new();
            if !gate {
                issues.push("health gate failed".to_owned());
            }
            if gate && !args["stable"].as_bool().unwrap_or(false) {
                issues.push("stabilization window incomplete".to_owned());
            }
            if !items(&args["regressed"]).is_empty() {
                issues.push(format!(
                    "healthy neighbors regressed: {}",
                    joined(&args["regressed"])
                ));
            }
            json!(if issues.is_empty() {
                "unknown post-deploy failure".to_owned()
            } else {
                issues.join("; ")
            })
        }
        "message" => {
            let mut issues = Vec::new();
            if !args["gateHealthy"].as_bool().unwrap_or(false) {
                issues.push(format!("health gate failed for {}", joined(&args["gates"])));
            }
            if !items(&args["regressed"]).is_empty() {
                issues.push(format!(
                    "healthy neighbors regressed: {}",
                    joined(&args["regressed"])
                ));
            }
            json!(if !issues.is_empty() {
                format!("Redeploy failed for {component}: {}", issues.join("; "))
            } else if component == "remote" {
                "Redeploy complete for remote via dns-owned release path".to_owned()
            } else {
                format!("Redeploy complete for {component}")
            })
        }
        "healthy" => json!(healthy(&args["snapshot"], component)),
        "ready" => json!(ready(&args["snapshot"], component, &args["disabled"])),
        "enabled" => {
            let config = &args["config"];
            let enabled = config["modules"][component]["enabled"]
                .as_bool()
                .unwrap_or(true);
            json!(
                enabled
                    && (component != "vpn" || config["vpn"]["enabled"].as_bool().unwrap_or(true))
            )
        }
        "policy" => {
            let config = &args["config"]["redeploy"];
            json!({"healthWaitMillis": number(config,"healthWaitSeconds").max(1)*1000,
                "healthRetryMillis": number(config,"healthRetrySeconds").max(1)*1000,
                "neighborGraceMillis": number(config,"neighborGraceSeconds").max(0)*1000})
        }
        "neighbors" => json!(neighbors(&args)),
        "regressions" => json!(
            neighbors(&args)
                .into_iter()
                .filter(|component| {
                    !healthy(&args["postMutation"], component.as_str().unwrap_or(""))
                })
                .collect::<Vec<_>>()
        ),
        "begin" => {
            json!({"deadline":number(&args,"now").wrapping_add(number(&args["policy"],"healthWaitMillis")),
            "watchedNeighbors":neighbors(&args),"targetHealthySince":null,"regressedNeighborSince":{}})
        }
        "step" => {
            let mut state = args["state"].clone();
            let now = number(&args, "now");
            let grace = number(&args["policy"], "neighborGraceMillis");
            let gate = items(&args["gates"]).iter().all(|component| {
                ready(
                    &args["snapshot"],
                    component.as_str().unwrap_or(""),
                    &args["disabled"],
                )
            });
            let current: Vec<_> = items(&state["watchedNeighbors"])
                .iter()
                .filter(|component| !healthy(&args["snapshot"], component.as_str().unwrap_or("")))
                .cloned()
                .collect();
            let mut persistent = Vec::new();
            let mut stable = false;
            if !gate {
                state["targetHealthySince"] = Value::Null;
                state["regressedNeighborSince"] = json!({});
            } else {
                if state["targetHealthySince"].is_null() {
                    state["targetHealthySince"] = json!(now);
                }
                let since = state["regressedNeighborSince"]
                    .as_object_mut()
                    .ok_or("invalid redeploy state")?;
                since.retain(|name, _| {
                    current
                        .iter()
                        .any(|component| component.as_str() == Some(name))
                });
                for component in &current {
                    let name = component.as_str().unwrap_or("");
                    let start = since
                        .entry(name.to_owned())
                        .or_insert(json!(now))
                        .as_i64()
                        .unwrap_or(now);
                    if now.wrapping_sub(start) >= grace {
                        persistent.push(component.clone());
                    }
                }
                stable = current.is_empty()
                    && now.wrapping_sub(state["targetHealthySince"].as_i64().unwrap_or(now))
                        >= grace;
            }
            let stability = stable || !persistent.is_empty();
            let terminal = stability || now >= number(&state, "deadline");
            let regressed = if stability { persistent } else { current };
            json!({"remainingSeconds":number(&state,"deadline").wrapping_sub(now).wrapping_div(1000).max(0),
                "state":state,"terminal":terminal,"success":stable,"gateHealthy":gate,
                "regressedNeighbors":regressed,"stabilityWindowSatisfied":stability})
        }
        _ => return Err("unknown redeploy policy operation".into()),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_NativeRedeployPolicy_decide(
    env: ::jni::JNIEnv,
    _class: ::jni::objects::JClass,
    operation: ::jni::objects::JString,
    payload: ::jni::objects::JString,
) -> ::jni::sys::jstring {
    crate::jni::bridge(env, "java/lang/IllegalStateException", |env| {
        decide(
            &crate::jni::text(env, &operation)?,
            &crate::jni::text(env, &payload)?,
        )
        .map(|v| Some(v.to_string()))
    })
}
