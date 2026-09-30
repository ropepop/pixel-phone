use serde_json::{Value, json};

fn s<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().unwrap_or_default()
}
fn ordinal(checkpoint: &Value) -> i64 {
    checkpoint["dispatchOrdinal"].as_i64().unwrap_or_default()
}
fn no_transition_phase(checkpoint: &Value) -> &'static str {
    if ordinal(checkpoint) >= 2 {
        "no_transition"
    } else {
        "retry_not_dispatched"
    }
}
fn no_transition_reason(checkpoint: &Value) -> &'static str {
    if no_transition_phase(checkpoint) == "no_transition" {
        "ticket_action_gesture_completed_no_transition"
    } else {
        "ticket_action_retry_not_dispatched"
    }
}
fn safe_to_clear(checkpoint: &Value, args: &Value) -> bool {
    let action = &args["action"];
    if s(checkpoint, "commandId") != s(args, "commandId")
        || s(checkpoint, "activationAttemptId") != s(action, "activationAttemptId")
        || s(action, "activationAttemptId") != s(action, "actionId")
        || action["terminal"] != true
        || crate::trim(s(action, "completedAt")).is_empty()
    {
        return false;
    }
    let activation_blank = crate::trim(s(action, "activationRevision")).is_empty();
    let attention = action["ok"] == false && s(action, "status") == "needs_attention";
    match s(checkpoint, "stage") {
        "FRESH_TICKET_PROVEN" => {
            ordinal(checkpoint) == 0
                && attention
                && s(action, "phase") == "not_dispatched"
                && activation_blank
        }
        "NO_TRANSITION_PROVEN" => {
            (1..=2).contains(&ordinal(checkpoint))
                && attention
                && s(action, "phase") == no_transition_phase(checkpoint)
                && s(action, "reason") == no_transition_reason(checkpoint)
                && s(action, "currentView") == "latest_unactivated"
                && activation_blank
        }
        "ACTIVATION_PROVEN" => {
            action["ok"] == true
                && s(action, "status") == "succeeded"
                && s(action, "phase") == "activation_proven"
                && s(action, "reason") == "ticket_action_registered"
                && s(action, "currentView") == "activated_current"
                && s(action, "interactionRevision") == s(checkpoint, "interactionRevision")
                && !activation_blank
                && s(action, "activationRevision") == s(checkpoint, "activationRevision")
        }
        _ => false,
    }
}

pub fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let input: Value =
        serde_json::from_str(payload).map_err(|_| "invalid checkpoint bridge JSON")?;
    let mut checkpoint = input["checkpoint"].clone();
    let args = &input["args"];
    let stage = s(&checkpoint, "stage");
    match operation {
        "no_transition_phase" | "no_transition_reason" => {
            if stage != "NO_TRANSITION_PROVEN" {
                return Err("Failed requirement.".into());
            }
            return Ok(json!(if operation == "no_transition_phase" {
                no_transition_phase(&checkpoint)
            } else {
                no_transition_reason(&checkpoint)
            }));
        }
        "failure_phase" => {
            return Ok(json!(if stage == "NO_TRANSITION_PROVEN" {
                no_transition_phase(&checkpoint)
            } else if ordinal(&checkpoint) > 0 || s(args, "provisionalPhase") == "outcome_unknown" {
                "outcome_unknown"
            } else {
                "not_dispatched"
            }));
        }
        "safe_to_clear" => return Ok(json!(safe_to_clear(&checkpoint, args))),
        "matches" => {
            return Ok(json!(
                s(&checkpoint, "commandId") == crate::trim(s(args, "commandId"))
                    && s(&checkpoint, "activationAttemptId")
                        == crate::trim(s(args, "activationAttemptId"))
                    && (args.get("interactionRevision").is_none()
                        || s(&checkpoint, "interactionRevision")
                            == crate::trim(s(args, "interactionRevision")))
            ));
        }
        "fresh" => {
            checkpoint = json!({
                "commandId": crate::trim(s(args, "commandId")),
                "interactionRevision": crate::trim(s(args, "interactionRevision")),
                "activationAttemptId": crate::trim(s(args, "activationAttemptId")),
                "activationRevision": "", "dispatchOrdinal": 0, "stage": "FRESH_TICKET_PROVEN"
            })
        }
        "dispatching" => {
            let next = args["ordinal"]
                .as_i64()
                .ok_or("activation dispatch ordinal must be one or two")?;
            if !(1..=2).contains(&next) {
                return Err("activation dispatch ordinal must be one or two".into());
            }
            if next <= ordinal(&checkpoint) {
                return Err("activation dispatch ordinal must advance".into());
            }
            if !(next == 1 && stage == "FRESH_TICKET_PROVEN"
                || next == 2 && stage == "NO_TRANSITION_PROVEN")
            {
                return Err(format!(
                    "activation dispatch stage does not admit ordinal {next}"
                ));
            }
            checkpoint["dispatchOrdinal"] = json!(next);
            checkpoint["stage"] = json!("ACTIVATION_DISPATCHING");
        }
        "proven" => {
            checkpoint["activationRevision"] = json!(crate::trim(s(args, "activationRevision")));
            checkpoint["stage"] = json!("ACTIVATION_PROVEN");
        }
        "no_transition" => checkpoint["stage"] = json!("NO_TRANSITION_PROVEN"),
        "attention" => {
            if matches!(stage, "FRESH_TICKET_PROVEN" | "NO_TRANSITION_PROVEN") {
                return Ok(json!({"checkpoint": checkpoint, "write": false}));
            }
            checkpoint["stage"] = json!("NEEDS_ATTENTION");
        }
        _ => return Err("unknown checkpoint bridge operation".into()),
    }
    for (key, message) in [
        ("commandId", "activation checkpoint command id is required"),
        (
            "interactionRevision",
            "activation checkpoint interaction revision is required",
        ),
        (
            "activationAttemptId",
            "activation checkpoint attempt id is required",
        ),
    ] {
        if crate::trim(s(&checkpoint, key)).is_empty() {
            return Err(message.into());
        }
    }
    Ok(json!({"checkpoint": checkpoint, "write": true}))
}
