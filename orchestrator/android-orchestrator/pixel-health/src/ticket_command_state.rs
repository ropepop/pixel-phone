//! Current command authority and terminal ACK policy; no socket, delivery or phone effect.
use serde_json::{Value, json};

fn expired(expiry: &Value, now: (i64, u32)) -> bool {
    expiry
        .as_str()
        .and_then(|v| crate::ticket_command::instant(crate::trim(v)))
        .is_none_or(|expiry| expiry <= now)
}
fn empty_inbox() -> Value {
    json!({"ready":false,"commands":{},"desired":null,"monitoring":null})
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let request: Value =
        serde_json::from_str(payload).map_err(|_| "invalid command state input")?;
    let now = (
        request["seconds"].as_i64().unwrap_or(0),
        request["nanos"].as_u64().unwrap_or(0) as u32,
    );
    let args = &request["args"];
    if operation == "expired" {
        return Ok(json!(expired(&args["expiresAt"], now)));
    }
    if operation == "start_dispatch" {
        return Ok(json!(
            args["commandType"] != "start"
                || (args["remotelyDispatchable"] == true && !expired(&args["expiresAt"], now))
        ));
    }
    if operation == "prioritize" {
        let mut commands = args["commands"]
            .as_array()
            .ok_or("missing commands")?
            .clone();
        if args["streamActive"] != true {
            commands.sort_by_key(|v| v["commandType"] != "start");
        }
        return Ok(json!(commands));
    }
    if operation == "ack" {
        if args["terminal"] != true
            || (args["commandType"] == "ticket_action_v3" && args["action"] == true)
            || (args["commandType"] == "vivi_reauth" && args["reauth"] == true)
            || (args["commandType"] == "generate_control_code" && args["ok"] == true)
        {
            return Ok(Value::Null);
        }
        let ok = args["ok"] == true;
        let reason = args["reason"].as_str().unwrap_or("");
        return Ok(json!({"status":if ok {"acknowledged"} else {"failed"},
            "reason":if crate::trim(reason).is_empty() { if ok {"pixel_direct_ack"} else {"pixel_direct_failed"} } else {reason}}));
    }
    let mut inbox = if request["inbox"].is_null() {
        empty_inbox()
    } else {
        request["inbox"].clone()
    };
    let mut error = Value::Null;
    let answer = match operation {
        "disconnect" => {
            inbox = empty_inbox();
            Value::Null
        }
        "apply" => {
            let message = &args["message"];
            if message["kind"] == "APPLIED" {
                inbox = empty_inbox();
                inbox["ready"] = json!(true);
            }
            if inbox["ready"] != true {
                error = json!("command_snapshot_not_ready");
            } else {
                let commands = inbox["commands"]
                    .as_object_mut()
                    .ok_or("invalid command inbox")?;
                if let Some(deleted) = message["deleted"].as_array() {
                    for id in deleted {
                        commands.shift_remove(id.as_str().ok_or("invalid command deletion")?);
                    }
                }
                if let Some(inserted) = message["commands"].as_array() {
                    for command in inserted {
                        commands.insert(
                            command["id"]
                                .as_str()
                                .ok_or("invalid command identifier")?
                                .into(),
                            command.clone(),
                        );
                    }
                }
                let count = commands.len();
                if message["desiredDeleted"] == true {
                    inbox["desired"] = Value::Null;
                }
                if !message["desired"].is_null() {
                    inbox["desired"] = message["desired"].clone();
                }
                if message["monitoringDeleted"] == true {
                    inbox["monitoring"] = Value::Null;
                }
                if !message["monitoring"].is_null() {
                    inbox["monitoring"] = message["monitoring"].clone();
                }
                if count > 128 {
                    error = json!("command_snapshot_capacity");
                }
            }
            Value::Null
        }
        "snapshot" => {
            if inbox["ready"] != true {
                Value::Null
            } else {
                let commands: Vec<_> = inbox["commands"]
                    .as_object()
                    .ok_or("invalid command inbox")?
                    .values()
                    .filter(|command| !expired(&command["expiresAt"], now))
                    .cloned()
                    .collect();
                json!({"commands":commands,"desired":inbox["desired"],"monitoring":inbox["monitoring"]})
            }
        }
        "contains" => {
            let command = &args["command"];
            let id = command["id"].as_str().ok_or("invalid command identifier")?;
            json!(
                inbox["ready"] == true
                    && inbox["commands"][id] == *command
                    && !expired(&command["expiresAt"], now)
            )
        }
        _ => return Err("unknown command state operation".into()),
    };
    Ok(json!({"inbox":inbox,"answer":answer,"error":error}))
}
