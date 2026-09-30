//! Metadata-only queue policy. Kotlin owns locks, payload DTOs, entropy and network effects.
use serde_json::{Value, json};

fn integer(value: &Value, key: &str) -> Result<i64, String> {
    value[key]
        .as_i64()
        .ok_or_else(|| format!("missing telemetry {key}"))
}
fn build_id(value: &str) -> bool {
    let parts: Vec<_> = value.split('.').collect();
    let ip = parts.len() == 4
        && parts.iter().all(|part| {
            !part.is_empty()
                && part.bytes().all(|c| c.is_ascii_digit())
                && part.parse::<i32>().is_ok_and(|v| (0..=255).contains(&v))
        });
    !ip && (1..=96).contains(&value.len())
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let args: Value =
        serde_json::from_str(payload).map_err(|_| "invalid telemetry policy input")?;
    match operation {
        "validate_draft" => {
            if (args["eventType"] == "CLEANUP_RESULT") == (args["cleanupCategory"] == "NONE") {
                return Err("cleanupCategory is required only for cleanup result events".into());
            }
            if !(0..=604_800_000).contains(&integer(&args, "durationMillis")?) {
                return Err("durationMillis must be between zero and seven days".into());
            }
            if !(0..=1_000_000_000).contains(&integer(&args, "count")?) {
                return Err("count exceeds the safe limit".into());
            }
            if !(0..=1_099_511_627_776).contains(&integer(&args, "byteCount")?) {
                return Err("byteCount exceeds one tebibyte".into());
            }
            Ok(Value::Null)
        }
        "validate_payload" => {
            if !args["correlationId"].is_null() {
                let id = args["correlationId"]
                    .as_str()
                    .ok_or("invalid telemetry correlation")?;
                if id.len() != 24
                    || !id
                        .bytes()
                        .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
                {
                    return Err("correlationId must be 24 lowercase hexadecimal characters".into());
                }
            }
            if !build_id(
                args["buildId"]
                    .as_str()
                    .ok_or("invalid telemetry build token")?,
            ) {
                return Err("buildId must be a bounded release token and not an IP address".into());
            }
            Ok(Value::Null)
        }
        "backoff" => {
            let count = integer(&args, "failureCount")?;
            if count <= 0 {
                return Err("failureCount must be positive".into());
            }
            let mut delay = integer(&args, "baseDelayMillis")?;
            let max = integer(&args, "maxDelayMillis")?;
            for _ in 0..(count - 1).min(62) {
                if delay >= max {
                    return Ok(json!(max));
                }
                delay = delay.wrapping_mul(2).min(max);
            }
            Ok(json!(delay))
        }
        "add" => {
            let left = integer(&args, "left")?;
            let right = integer(&args, "right")?;
            Ok(json!(if right > 0 && left > i64::MAX - right {
                i64::MAX
            } else {
                left.wrapping_add(right)
            }))
        }
        "evict" | "ready" | "expired" => {
            let queue = args["queue"].as_array().ok_or("missing telemetry queue")?;
            let now = integer(&args, "now")?;
            if operation == "expired" {
                let age = integer(&args, "maxEventAgeMillis")?;
                return Ok(json!(
                    queue
                        .iter()
                        .enumerate()
                        .filter_map(|(i, v)| {
                            let created = v["created"].as_i64()?;
                            (v["inFlight"] != true
                                && now >= created
                                && now.wrapping_sub(created) >= age)
                                .then_some(i)
                        })
                        .collect::<Vec<_>>()
                ));
            }
            let mut candidates: Vec<_> = queue
                .iter()
                .enumerate()
                .filter(|(_, v)| v["inFlight"] != true)
                .collect();
            if operation == "ready" {
                candidates.retain(|(_, v)| v["next"].as_i64().is_some_and(|next| next <= now));
                candidates.sort_by_key(|(index, v)| {
                    (
                        -v["priority"].as_i64().unwrap_or(0),
                        v["created"].as_i64().unwrap_or(0),
                        *index,
                    )
                });
                return Ok(json!(candidates.first().map(|(index, _)| *index)));
            }
            let bytes = integer(&args, "serializedBytes")?;
            let max = integer(&args, "maxQueueBytes")?;
            if bytes > max {
                return Ok(json!({"drop":"EVENT_TOO_LARGE","indices":[]}));
            }
            let priority = integer(&args, "priority")?;
            let needed = (integer(&args, "queuedBytes")? + bytes - max).max(0);
            candidates.retain(|(_, v)| v["priority"].as_i64().is_some_and(|v| v <= priority));
            candidates.sort_by_key(|(index, v)| {
                (
                    v["priority"].as_i64().unwrap_or(0),
                    v["created"].as_i64().unwrap_or(0),
                    *index,
                )
            });
            let mut selected = Vec::new();
            let mut freed = 0;
            for (index, value) in candidates {
                if freed >= needed {
                    break;
                }
                selected.push(index);
                freed += integer(value, "bytes")?;
            }
            Ok(
                json!({"drop":if freed < needed {Some("HIGHER_PRIORITY_BACKLOG")} else {None},"indices":selected}),
            )
        }
        _ => Err("unknown telemetry policy operation".into()),
    }
}
