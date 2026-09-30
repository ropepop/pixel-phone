//! Cleanup caller decisions. Android owns root I/O, timestamps and one cleanup invocation.
use serde_json::{Value, json};
use std::collections::{BTreeSet, HashSet};
fn items(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or("")
}
fn number(v: &Value, key: &str) -> i64 {
    v[key].as_i64().unwrap_or(0)
}
fn byte_sum(v: &Value) -> i64 {
    items(v).iter().fold(0_i64, |total, item| {
        total.wrapping_add(number(item, "bytes"))
    })
}
fn compare(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}
fn summary(v: &Value) -> Value {
    let fields = ["candidates", "deletedPaths", "skippedPaths", "failurePaths"];
    let mut categories: BTreeSet<&str> = BTreeSet::new();
    for field in fields {
        for entry in items(&v[field]) {
            categories.insert(text(entry, "category"));
        }
    }
    let mut categories: Vec<_> = categories.into_iter().collect();
    categories.sort_by(|a, b| compare(a, b));
    let categories:Vec<_>=categories.into_iter().map(|category|{
        let selected=|field:&str|items(&v[field]).iter().filter(|item|text(item,"category")==category).collect::<Vec<_>>();
        let candidates=selected("candidates");let deleted=selected("deletedPaths");
        let sum=|values:&[&Value]|values.iter().fold(0_i64,|total,item|total.wrapping_add(number(item,"bytes")));
        json!({"category":category,"candidates":candidates.len(),"candidateBytes":sum(&candidates),"deleted":deleted.len(),"deletedBytes":sum(&deleted),"skipped":selected("skippedPaths").len(),"failures":selected("failurePaths").len()})
    }).collect();
    json!({"protectedCount":items(&v["protectedPaths"]).len(),"candidateCount":items(&v["candidates"]).len(),"candidateBytes":byte_sum(&v["candidates"]),"deletedCount":items(&v["deletedPaths"]).len(),"deletedBytes":byte_sum(&v["deletedPaths"]),"skippedCount":items(&v["skippedPaths"]).len(),"failureCount":items(&v["failurePaths"]).len(),"categories":categories})
}
fn local_path(url: &str) -> Option<&str> {
    let url = crate::trim(url);
    if url.to_ascii_lowercase().starts_with("file://") {
        Some(url.strip_prefix("file://").unwrap_or(url))
    } else if url.starts_with('/') {
        Some(url)
    } else {
        None
    }
}
fn first_detail(v: &Value, key: &str, default: &str) -> String {
    items(&v[key])
        .first()
        .map(|v| text(v, "detail").to_owned())
        .unwrap_or_else(|| default.to_owned())
}
fn message(v: &Value) -> String {
    let s = &v["summary"];
    match text(v, "status") {
        "completed" => format!(
            "Cleanup complete: removed {} paths and reclaimed {} bytes.",
            number(s, "deletedCount"),
            number(s, "deletedBytes")
        ),
        "dry_run" => format!(
            "Cleanup dry-run complete: {} paths are eligible, totaling {} bytes.",
            number(s, "candidateCount"),
            number(s, "candidateBytes")
        ),
        "skipped" => format!(
            "Cleanup skipped: {}",
            first_detail(v, "skippedPaths", "unknown reason")
        ),
        _ => format!(
            "Cleanup failed: {}",
            first_detail(v, "failurePaths", "unknown failure")
        ),
    }
}
fn frequent_message(v: &Value) -> String {
    if v["deferred"].as_bool().unwrap_or(false) {
        "Frequent maintenance deferred while another runtime mutation is active".into()
    } else if text(v, "status") == "failed" {
        "Frequent maintenance failed safely".into()
    } else if number(&v["summary"], "deletedCount") > 0 {
        format!(
            "Frequent maintenance removed {} expired or oversized items",
            number(&v["summary"], "deletedCount")
        )
    } else {
        "Frequent maintenance limits are satisfied".into()
    }
}
fn cohort(path: &str) -> Option<String> {
    for (offset, _) in path.char_indices() {
        let tail = &path[offset..];
        let prefix = if tail.starts_with("site-notifier-") || tail.starts_with("site_notifier-") {
            14
        } else {
            continue;
        };
        let bytes = tail.as_bytes();
        let end = prefix + 8 + 1 + 6 + 1;
        if bytes.len() >= end
            && bytes[prefix..prefix + 8].iter().all(u8::is_ascii_digit)
            && bytes[prefix + 8] == b'T'
            && bytes[prefix + 9..prefix + 15]
                .iter()
                .all(u8::is_ascii_digit)
            && bytes[prefix + 15] == b'Z'
        {
            return Some(tail[..end].replace('_', "-"));
        }
    }
    None
}
pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let mut args: Value =
        serde_json::from_str(payload).map_err(|_| "invalid cleanup policy input")?;
    Ok(match operation {
        "parse_script" => {
            let mut candidates = Vec::new();
            let mut deleted = Vec::new();
            let mut skipped = Vec::new();
            let mut failures = Vec::new();
            let mut observations = serde_json::Map::new();
            for line in text(&args, "stdout")
                .split(['\r', '\n'])
                .map(|line| line.trim_end_matches(crate::whitespace))
                .filter(|line| !crate::trim(line).is_empty())
            {
                let parts: Vec<_> = line.splitn(5, '\t').collect();
                if parts.len() < 4 {
                    continue;
                }
                let bytes = parts[2].parse::<i64>().unwrap_or(0);
                let record = json!({"category":parts[1],"path":parts[3],"bytes":bytes,"detail":parts.get(4).copied().unwrap_or("")});
                match parts[0] {
                    "CANDIDATE" => candidates.push(record),
                    "DELETE" => deleted.push(record),
                    "SKIP" => skipped.push(record),
                    "FAIL" => failures.push(record),
                    "OBSERVE" => {
                        observations.insert(parts[1].to_owned(), json!(bytes));
                    }
                    _ => {}
                }
            }
            json!({"candidates":candidates,"deletedPaths":deleted,"skippedPaths":skipped,"failures":failures,"observations":observations})
        }
        "summary" => summary(&args),
        "report" => {
            args["summary"] = summary(&args);
            for key in [
                "protectedPaths",
                "candidates",
                "deletedPaths",
                "skippedPaths",
                "failurePaths",
            ] {
                let mut values = items(&args[key]).to_vec();
                values.sort_by(|a, b| compare(text(a, "path"), text(b, "path")));
                args[key] = json!(values);
            }
            args
        }
        "frequent_report" => {
            let output = &args["output"];
            let mut input = output.clone();
            input["failurePaths"] = output["failures"].clone();
            let result = json!({"startedAt":args["startedAt"],"finishedAt":args["finishedAt"],"status":args["status"],"deferred":args["deferred"],"failureReason":args["failureReason"],"rootHistoryBytes":number(&output["observations"],"superuser_log_db"),"stackLogBytes":number(&output["observations"],"runtime_log_total"),"summary":summary(&input)});
            result
        }
        "outcome" => json!({"success":text(&args,"status")!="failed","message":message(&args)}),
        "frequent_outcome" => {
            json!({"success":text(&args,"status")!="failed","message":frequent_message(&args)})
        }
        "finish_status" => json!(if number(&args, "failures") > 0 {
            "failed"
        } else if args["dryRun"].as_bool().unwrap_or(false) {
            "dry_run"
        } else {
            "completed"
        }),
        "protected_paths" => {
            let mut paths = HashSet::new();
            let mut result = Vec::new();
            for record in items(&args["paths"]) {
                let path = crate::trim(text(record, "path"));
                if !path.is_empty() && paths.insert(path.to_owned()) {
                    let mut record = record.clone();
                    record["path"] = json!(path);
                    result.push(record);
                }
            }
            json!(result)
        }
        "parse_releases" => {
            let mut current = "";
            let mut releases = Vec::new();
            for line in text(&args, "stdout").split(['\r', '\n']) {
                if let Some((kind, value)) = line.split_once('\t') {
                    match kind {
                        "CURRENT" => current = value,
                        "RELEASE" => releases.push(value),
                        _ => {}
                    }
                }
            }
            json!({"currentPath":current,"releasePaths":releases})
        }
        "protect_releases" => {
            let current = text(&args, "currentPath");
            let component = text(&args, "component");
            let releases = items(&args["releasePaths"]);
            let previous = releases
                .iter()
                .filter_map(Value::as_str)
                .find(|path| *path != current)
                .or_else(|| releases.first().and_then(Value::as_str));
            let mut paths = Vec::new();
            if !crate::trim(current).is_empty() {
                paths.push(json!({"category":"release_dir","path":current,"detail":format!("current release:{component}")}));
            }
            if let Some(previous) = previous {
                if previous != current {
                    paths.push(json!({"category":"release_dir","path":previous,"detail":format!("rollback release:{component}")}));
                }
                if crate::trim(current).is_empty() {
                    paths.push(json!({"category":"release_dir","path":previous,"detail":format!("fallback protected release:{component}")}));
                }
            }
            json!(paths)
        }
        "recent_paths" => {
            let mut result = Vec::new();
            let mut keys = HashSet::new();
            for path in items(&args["notifier"]).iter().filter_map(Value::as_str) {
                if let Some(key) = cohort(path)
                    && (keys.contains(&key) || keys.len() < 2)
                {
                    keys.insert(key);
                    result.push(json!({"category":"termux_artifact","path":path,"detail":"recent Termux notifier cohort"}));
                }
            }
            for (kind, detail) in [
                ("runtime", "recent Termux orchestrator snapshot"),
                ("build", "recent Termux build dir"),
            ] {
                for path in items(&args[kind]).iter().take(2) {
                    result.push(json!({"category":"termux_artifact","path":path,"detail":detail}));
                }
            }
            json!(result)
        }
        "list_paths" => json!(
            text(&args, "stdout")
                .split(['\r', '\n'])
                .map(crate::trim)
                .filter(|path| !path.is_empty())
                .collect::<Vec<_>>()
        ),
        "local_path" => json!(local_path(text(&args, "url"))),
        "local_url" => {
            json!(local_path(text(&args, "url")).is_some_and(|path| path.starts_with('/')))
        }
        "manifest_error" => {
            let manifest = &args["manifest"];
            let kind = text(&args, "kind");
            let component = text(&args, "component");
            let mut failure = None;
            if kind == "component" {
                if text(manifest, "componentId") != component {
                    failure = Some(format!("Component manifest mismatch for {component}"));
                } else if crate::trim(text(manifest, "releaseId")).is_empty() {
                    failure = Some(format!("Component release id is required for {component}"));
                } else if items(&manifest["artifacts"]).is_empty() {
                    failure = Some(format!(
                        "Component release artifacts are required for {component}"
                    ));
                }
            } else if crate::trim(text(manifest, "manifestVersion")).is_empty() {
                failure = Some(
                    if kind == "rollback" {
                        "Rollback runtime manifest version is required"
                    } else {
                        "Runtime manifest version is required"
                    }
                    .to_owned(),
                );
            } else if kind != "rollback" && items(&manifest["artifacts"]).is_empty() {
                failure = Some("Runtime manifest artifacts are required".to_owned());
            }
            if failure.is_none() {
                for entry in items(&manifest["artifacts"]) {
                    if !local_path(text(entry, "url")).is_some_and(|path| path.starts_with('/')) {
                        failure = Some(match kind {
                            "component" => format!(
                                "Component artifact {} must use a local path",
                                text(entry, "id")
                            ),
                            "rollback" => {
                                "Rollback runtime artifact must use a local path".to_owned()
                            }
                            _ => format!(
                                "Runtime artifact {} must use a local path",
                                text(entry, "id")
                            ),
                        });
                        break;
                    }
                }
            }
            json!(failure)
        }
        "health_protocol" => {
            let stdout = text(&args, "stdout");
            let lines = stdout.split(['\r', '\n']).collect::<Vec<_>>();
            let path = lines
                .iter()
                .find_map(|line| line.strip_prefix("REPORT_PATH\t"))
                .map(crate::trim)
                .unwrap_or("");
            let body = lines
                .iter()
                .position(|line| line.starts_with("REPORT_BODY"))
                .map(|at| lines[at + 1..].join("\n"))
                .unwrap_or_default();
            let body = crate::trim(&body);
            let reason = if !args["ok"].as_bool().unwrap_or(false) {
                "report_probe_failed"
            } else if crate::trim(stdout).is_empty() {
                "no_report"
            } else if body.is_empty() {
                "report_body_missing"
            } else {
                ""
            };
            let mut details = serde_json::Map::new();
            if reason == "report_probe_failed" {
                let detail = crate::trim(text(&args, "stderr"));
                details.insert(
                    "detail".into(),
                    json!(if detail.is_empty() { "unknown" } else { detail }),
                );
            } else if reason == "report_body_missing" {
                details.insert(
                    "report_path".into(),
                    json!(if path.is_empty() { "unknown" } else { path }),
                );
            }
            json!({"path":path,"body":body,"reason":reason,"details":details})
        }
        "health_result" => {
            let report = &args["report"];
            let age = number(&args, "age");
            let failed = text(report, "status") == "failed";
            let dry = report["dryRun"].as_bool().unwrap_or(false);
            let stale = !(0..=8 * 24 * 60 * 60).contains(&age);
            let healthy = !failed && !dry && !stale;
            let reason = if failed {
                "latest_cleanup_failed"
            } else if dry {
                "latest_cleanup_was_dry_run"
            } else if stale {
                "latest_cleanup_stale"
            } else {
                "ok"
            };
            let path = text(&args, "path");
            json!({"healthy":healthy,"status":if healthy{"running"}else{"degraded"},"details":{"failure_reason":reason,"report_path":if crate::trim(path).is_empty(){"unknown"}else{path},"report_status":report["status"],"report_age_sec":age.to_string(),"deleted_bytes":number(&report["summary"],"deletedBytes").to_string(),"deleted_count":number(&report["summary"],"deletedCount").to_string(),"dry_run":dry.to_string()}})
        }
        "degraded" => {
            let details = args["details"].as_object().cloned().unwrap_or_default();
            let mut base = serde_json::Map::new();
            base.insert("failure_reason".into(), args["reason"].clone());
            for (key, value) in details {
                base.insert(key, value);
            }
            json!({"healthy":false,"status":"degraded","details":base})
        }
        "durable_report" => {
            for key in [
                "protectedPaths",
                "candidates",
                "deletedPaths",
                "skippedPaths",
                "failurePaths",
            ] {
                args[key] = json!([]);
            }
            args
        }
        _ => return Err("unknown cleanup policy operation".into()),
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_NativeCleanupPolicy_decide(
    env: ::jni::JNIEnv,
    _class: ::jni::objects::JClass,
    operation: ::jni::objects::JString,
    payload: ::jni::objects::JString,
) -> ::jni::sys::jstring {
    crate::jni::bridge(env, "java/lang/IllegalArgumentException", |env| {
        decide(
            &crate::jni::text(env, &operation)?,
            &crate::jni::text(env, &payload)?,
        )
        .map(|v| Some(v.to_string()))
    })
}
