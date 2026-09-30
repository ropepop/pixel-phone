//! Read-only monitoring cadence. Capture, delivery and action authority remain Android-owned.
use serde_json::{Value, json};

const INTERVAL: i64 = 300_000;
fn number(value: &Value, key: &str) -> i64 {
    value[key].as_i64().unwrap_or(0)
}
fn fresh(observation: &Value, now: i64) -> bool {
    let captured = number(observation, "capturedAtMillis");
    captured > 0 && (0..30_000).contains(&now.wrapping_sub(captured))
}
fn empty_schedule(config: Value, now: i64) -> Value {
    json!({"config":config,"lastReported":null,"lastReportedAt":0,"lastCheckedAt":null,
        "nextRecheckAt":0,"recheckUntil":0,"acceptCapturedAfter":now})
}
fn regular_due(schedule: &Value, now: i64) -> bool {
    schedule["lastCheckedAt"]
        .as_i64()
        .is_none_or(|last| now.wrapping_sub(last) >= INTERVAL)
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let request: Value =
        serde_json::from_str(payload).map_err(|_| "invalid monitoring bridge input")?;
    let now = request["now"].as_i64().ok_or("missing monitoring time")?;
    let observation = &request["observation"];
    let mut schedule = if request["schedule"].is_null() {
        empty_schedule(Value::Null, 0)
    } else {
        request["schedule"].clone()
    };
    let answer = match operation {
        "observe" => {
            let reason = if request["busy"] == true {
                "busy"
            } else {
                match request["state"].as_str() {
                    None => "capture_unavailable",
                    Some("ACTIVATED_DETAIL") => "ticket_detail_activated",
                    Some("UNACTIVATED_DETAIL") => "ticket_detail_unused",
                    Some("LOGIN_REQUIRED") => "login",
                    Some("BLOCKED") => "blocked",
                    Some("TICKET_LIST" | "TICKETS_SINGLE_USE_EMPTY" | "TICKETS_TIME_EMPTY") => {
                        "ticket_list"
                    }
                    _ => "unknown",
                }
            };
            let status = match reason {
                "ticket_detail_activated" | "ticket_detail_unused" => "ready",
                "capture_unavailable" => "unavailable",
                _ => "not_ready",
            };
            return Ok(json!({"status":status,"reason":reason,"capturedAtMillis":now}));
        }
        "fresh" => json!(fresh(observation, now)),
        "configure" => {
            if schedule["config"] != request["config"] {
                schedule = empty_schedule(request["config"].clone(), now);
            }
            json!(request["config"]["enabled"] == true)
        }
        "check_due" => json!(
            (observation.is_null() && regular_due(&schedule, now))
                || (number(&schedule, "nextRecheckAt") > 0
                    && now >= number(&schedule, "nextRecheckAt")
                    && now <= number(&schedule, "recheckUntil"))
        ),
        "checked" => {
            if regular_due(&schedule, now) {
                schedule["lastCheckedAt"] = json!(now);
            }
            if number(&schedule, "nextRecheckAt") > 0 {
                schedule["nextRecheckAt"] = json!(now.wrapping_add(15_000));
            }
            schedule["acceptCapturedAfter"] = json!(now);
            Value::Null
        }
        "should_report" => {
            if observation.is_null() {
                return Err("missing monitoring observation".into());
            }
            json!(
                fresh(observation, now)
                    && number(observation, "capturedAtMillis")
                        >= number(&schedule, "acceptCapturedAfter")
                    && number(observation, "capturedAtMillis")
                        >= number(&schedule["lastReported"], "capturedAtMillis")
                    && (schedule["lastReported"]["status"] != observation["status"]
                        || schedule["lastReported"]["reason"] != observation["reason"]
                        || now.wrapping_sub(number(&schedule, "lastReportedAt")) >= INTERVAL)
            )
        }
        "reported" => {
            if observation.is_null() {
                return Err("missing monitoring observation".into());
            }
            if observation["status"] == "ready" {
                schedule["nextRecheckAt"] = json!(0);
                schedule["recheckUntil"] = json!(0);
            } else if number(&schedule, "recheckUntil") == 0
                || now.wrapping_sub(number(&schedule, "lastReportedAt")) >= INTERVAL
            {
                schedule["nextRecheckAt"] = json!(now.wrapping_add(15_000));
                schedule["recheckUntil"] = json!(now.wrapping_add(180_000));
            }
            schedule["lastReported"] = observation.clone();
            schedule["lastReportedAt"] = json!(now);
            schedule["lastCheckedAt"] = json!(now);
            Value::Null
        }
        _ => return Err("unknown monitoring bridge operation".into()),
    };
    Ok(json!({"schedule":schedule,"answer":answer}))
}
