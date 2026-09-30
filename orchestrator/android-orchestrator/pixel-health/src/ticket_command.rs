//! Bounded subscription decoding only. Kotlin owns the socket, current inbox,
//! durable journals, result delivery and every phone effect.
use chrono::DateTime;
use serde_json::{Value, json};

const COMMAND_TABLE: &str = "ticketremote_service_stream_command";
const DESIRED_TABLE: &str = "ticketremote_service_stream_desired_state";
const MONITORING_TABLE: &str = "ticketremote_monitoring_config";
pub(crate) const MAX_MESSAGE_CHARS: usize = 768 * 1024;
const COMMAND_FIELDS: [&str; 11] = [
    "id",
    "ticketId",
    "backendId",
    "commandType",
    "status",
    "revision",
    "reason",
    "payloadJson",
    "createdAt",
    "updatedAt",
    "expiresAt",
];
const DESIRED_FIELDS: [&str; 13] = [
    "id",
    "ticketId",
    "backendId",
    "desiredActive",
    "viewerCount",
    "reason",
    "revision",
    "updatedBy",
    "updatedAt",
    "coldRestartId",
    "coldRestartPhase",
    "coldRestartStartedAt",
    "coldRestartError",
];

// Kotlin's JSON tree reader retains every unquoted primitive literally (including
// nonfinite numbers and unknown tokens). Tag values before serde validates JSON
// structure/escapes, preserving that spelling and whether the primitive was
// quoted. All original strings receive S:, so inputs cannot forge the L: tag.
pub(crate) fn parse(text: &str) -> Result<Value, ()> {
    let bytes = text.as_bytes();
    let mut adapted = String::with_capacity(text.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'"' {
            let start = index;
            index += 1;
            while index < bytes.len() {
                match bytes[index] {
                    b'\\' => index += 2,
                    b'"' => break,
                    _ => index += 1,
                }
            }
            if index >= bytes.len() {
                return Err(());
            }
            index += 1;
            let key = bytes[index..]
                .iter()
                .find(|byte| !matches!(byte, b' ' | b'\t' | b'\n' | b'\r'))
                == Some(&b':');
            adapted.push('"');
            if !key {
                adapted.push_str("S:");
            }
            adapted.push_str(&text[start + 1..index]);
        } else if matches!(
            bytes[index],
            b'{' | b'}' | b'[' | b']' | b',' | b':' | b' ' | b'\t' | b'\n' | b'\r'
        ) {
            adapted.push(bytes[index] as char);
            index += 1;
        } else {
            let start = index;
            while index < bytes.len()
                && !matches!(
                    bytes[index],
                    b'{' | b'}' | b'[' | b']' | b',' | b':' | b'"' | b' ' | b'\t' | b'\n' | b'\r'
                )
            {
                index += 1;
            }
            let key = bytes[index..]
                .iter()
                .find(|byte| !matches!(byte, b' ' | b'\t' | b'\n' | b'\r'))
                == Some(&b':');
            if key {
                adapted.push_str(&text[start..index]);
            } else {
                adapted.push_str(
                    &serde_json::to_string(&format!("L:{}", &text[start..index]))
                        .map_err(|_| ())?,
                );
            }
        }
    }
    serde_json::from_str(&adapted).map_err(|_| ())
}
fn primitive_content(value: &Value) -> Option<&str> {
    let text = value.as_str()?;
    text.strip_prefix("S:").or_else(|| text.strip_prefix("L:"))
}
fn content(value: &Value) -> Option<String> {
    if value.as_str() == Some("L:null") {
        None
    } else {
        primitive_content(value).map(str::to_owned)
    }
}
fn quoted(value: &Value) -> Option<&str> {
    value.as_str()?.strip_prefix("S:")
}
fn string(row: &Value, field: &str) -> String {
    content(&row[field]).unwrap_or_default()
}
fn optional(value: &Value) -> String {
    match value {
        Value::Array(values)
            if values.len() == 2 && content(&values[0]).as_deref() == Some("0") =>
        {
            content(&values[1]).unwrap_or_default()
        }
        Value::Object(values) => values.get("some").map(optional).unwrap_or_default(),
        _ => content(value).unwrap_or_default(),
    }
}
fn fields(row: Value, names: &[&str], error: &'static str) -> Result<Value, String> {
    match row {
        Value::Object(_) => Ok(row),
        Value::Array(values) if values.len() == names.len() => Ok(Value::Object(
            names
                .iter()
                .zip(values)
                .map(|(name, value)| ((*name).into(), value))
                .collect(),
        )),
        _ => Err(error.into()),
    }
}
fn matches(row: &Value, ticket: &str, backend: &str) -> bool {
    string(row, "id") == format!("{ticket}:{backend}")
        && string(row, "ticketId") == ticket
        && string(row, "backendId") == backend
}
fn boolean(value: &Value) -> Option<bool> {
    content(value)?.parse().ok()
}
// JsonPrimitive.intOrNull uses the JSON integer lexer: exponent notation is
// accepted, decimal points are rejected, and quoted primitives use the same path.
fn integer(value: &Value) -> Option<i32> {
    integer_long(value)?.try_into().ok()
}

pub(crate) fn integer_long(value: &Value) -> Option<i64> {
    let text = content(value)?;
    let text = text.trim_start_matches([' ', '\t', '\n', '\r']);
    let quoted = text.starts_with('"');
    let text = if quoted { &text[1..] } else { text };
    let end = text
        .find([' ', '\t', '\n', '\r', ',', '}', ']', ':', '"'])
        .unwrap_or(text.len());
    if quoted && text.as_bytes().get(end) != Some(&b'"') {
        return None;
    }
    let text = &text[..end];
    let (mantissa, exponent) = text
        .find(['e', 'E'])
        .map(|index| (&text[..index], Some(&text[index + 1..])))
        .unwrap_or((text, None));
    let negative = mantissa.starts_with('-');
    let digits = mantissa.strip_prefix('-').unwrap_or(mantissa);
    if digits.is_empty() || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let magnitude = digits.parse::<u64>().ok()?;
    if magnitude > 1_u64 << 63 {
        return None;
    }
    let number = if let Some(exponent) = exponent {
        let exponent = if exponent.is_empty() {
            0
        } else {
            exponent.parse::<i64>().ok()?
        };
        let number = -(magnitude as f64) * 10_f64.powf(exponent as f64);
        if !number.is_finite()
            || number.floor() != number
            || number < i64::MIN as f64
            || number > i64::MAX as f64
        {
            return None;
        }
        let number = number as i64;
        if negative {
            number
        } else {
            number.checked_neg()?
        }
    } else if negative {
        (-(magnitude as i128)).try_into().ok()?
    } else {
        magnitude.try_into().ok()?
    };
    Some(number)
}

// ISO_INSTANT permits midnight 24:00 and expanded years. Reuse chrono's date,
// fraction and offset validation after mapping the Gregorian 400-year cycle;
// this also retains Java's nanosecond and normalized leap-second comparison.
pub(crate) fn instant(text: &str) -> Option<(i64, u32)> {
    let text = crate::trim(text).to_ascii_uppercase();
    let time = text.find('T')?;
    let year_text = text[..time].rsplitn(3, '-').nth(2)?;
    let year = year_text.parse::<i64>().ok()?;
    if !(-1_000_000_000..=1_000_000_000).contains(&year) {
        return None;
    }
    let digits = year_text.strip_prefix(['+', '-']).unwrap_or(year_text);
    if !digits.bytes().all(|byte| byte.is_ascii_digit())
        || digits.len() < 4
        || (year_text.starts_with('+') && digits.len() <= 4)
        || (!year_text.starts_with(['+', '-']) && digits.len() != 4)
        || (year_text.starts_with('-') && year == 0)
    {
        return None;
    }
    let mapped_year = year.rem_euclid(400) + 2000;
    let mut normalized = format!("{mapped_year:04}{}", &text[year_text.len()..]);
    let time = normalized.find('T')?;
    let midnight = normalized.get(time + 1..time + 9) == Some("24:00:00");
    if midnight {
        normalized.replace_range(time + 1..time + 3, "00");
    }
    let fraction = normalized.get(time + 9..)?;
    if let Some(fraction) = fraction.strip_prefix('.')
        && fraction.bytes().take_while(u8::is_ascii_digit).count() > 9
    {
        return None;
    }
    let parsed = DateTime::parse_from_rfc3339(&normalized)
        .or_else(|_| DateTime::parse_from_str(&normalized, "%Y-%m-%dT%H:%M:%S%.f%::z"))
        .ok()?;
    if parsed.offset().local_minus_utc().abs() > 18 * 3600 {
        return None;
    }
    if midnight && parsed.timestamp_subsec_nanos() != 0 {
        return None;
    }
    if parsed.timestamp_subsec_nanos() >= 1_000_000_000
        && normalized.get(time + 1..time + 9) != Some("23:59:60")
    {
        return None;
    }
    let cycle_seconds = ((year - mapped_year) / 400).checked_mul(146_097 * 86_400)?;
    Some((
        parsed
            .timestamp()
            .checked_add(cycle_seconds)?
            .checked_add(if midnight { 86_400 } else { 0 })?,
        parsed.timestamp_subsec_nanos() % 1_000_000_000,
    ))
}
fn monitoring(row: Value, ticket: &str, backend: &str) -> Result<Option<Value>, String> {
    let row = fields(
        row,
        &["id", "ticketId", "backendId", "enabled", "epoch"],
        "invalid_monitoring_config_shape",
    )?;
    if !matches(&row, ticket, backend) {
        return Ok(None);
    }
    let epoch = string(&row, "epoch");
    if crate::trim(&epoch).is_empty() || epoch.encode_utf16().count() > 128 {
        return Err("invalid_monitoring_epoch".into());
    }
    let enabled = boolean(&row["enabled"]).ok_or("invalid_monitoring_enabled")?;
    Ok(Some(json!({"enabled":enabled,"epoch":epoch})))
}
fn desired(row: Value, ticket: &str, backend: &str) -> Result<Option<Value>, String> {
    let row = fields(row, &DESIRED_FIELDS, "invalid_desired_state_shape")?;
    if !matches(&row, ticket, backend) {
        return Ok(None);
    }
    let phase = optional(&row["coldRestartPhase"]);
    if ![
        "",
        "quiescing",
        "stopping",
        "confirmed",
        "reloading",
        "asleep",
        "live",
        "failed",
    ]
    .contains(&phase.as_str())
    {
        return Err("invalid_cold_restart_phase".into());
    }
    let active = boolean(&row["desiredActive"]).ok_or("invalid_desired_active")?;
    let viewers = integer(&row["viewerCount"]).ok_or("invalid_desired_viewers")?;
    Ok(Some(
        json!({"desiredActive":active,"viewerCount":viewers,"reason":string(&row,"reason"),
        "revision":string(&row,"revision"),"updatedAt":string(&row,"updatedAt"),
        "coldRestartId":optional(&row["coldRestartId"]),"coldRestartPhase":phase}),
    ))
}
fn command(row: Value, ticket: &str, backend: &str, now: (i64, u32)) -> Option<Value> {
    let row = fields(row, &COMMAND_FIELDS, "invalid_command_shape").ok()?;
    if COMMAND_FIELDS
        .iter()
        .any(|field| quoted(&row[*field]).is_none())
    {
        return None;
    }
    let id = quoted(&row["id"])?;
    let payload = quoted(&row["payloadJson"])?;
    let kind = quoted(&row["commandType"])?;
    let expires = instant(quoted(&row["expiresAt"])?)?;
    if crate::trim(id).is_empty()
        || id.encode_utf16().count() > 512
        || quoted(&row["ticketId"]) != Some(ticket)
        || quoted(&row["backendId"]) != Some(backend)
        || ![
            "start",
            "cold_stop",
            "ticket_action_v3",
            "generate_control_code",
            "control_code_browser_capture",
            "vivi_reauth",
        ]
        .contains(&kind)
        || quoted(&row["status"]) != Some("pending")
        || payload.len() > 8 * 1024
        || expires <= now
    {
        return None;
    }
    let blank_payload = crate::trim(payload).is_empty();
    let mut result = Value::Object(
        COMMAND_FIELDS
            .iter()
            .map(|field| ((*field).into(), json!(quoted(&row[*field]).unwrap())))
            .collect(),
    );
    if blank_payload {
        result["payloadJson"] = json!("{}");
    }
    Some(result)
}
fn message(kind: &str) -> Value {
    json!({"kind":kind})
}

pub fn decode(
    raw: &str,
    ticket: &str,
    backend: &str,
    seconds: i64,
    nanos: u32,
) -> Result<Value, String> {
    if raw.encode_utf16().count() > MAX_MESSAGE_CHARS || raw.len() > 1024 * 1024 {
        return Ok(message("ERROR"));
    }
    let root: Value = match parse(raw) {
        Ok(Value::Object(root)) => Value::Object(root),
        _ => return Ok(message("ERROR")),
    };
    // Identity bodies contain authentication material. Never project them or
    // include source text in errors; only the existing protocol marker crosses back.
    if root.get("IdentityToken").is_some() {
        return Ok(message("IDENTITY"));
    }
    if root.get("SubscriptionError").is_some() {
        return Ok(message("ERROR"));
    }
    let (kind, update) = if let Some(initial) = root.get("InitialSubscription") {
        if !initial.is_object()
            || integer(&initial["request_id"]) != Some(0)
            || !initial["database_update"].is_object()
        {
            return Ok(message("ERROR"));
        }
        ("APPLIED", &initial["database_update"])
    } else if let Some(transaction) = root.get("TransactionUpdate") {
        let committed = &transaction["status"]["Committed"];
        if !committed.is_object() {
            return Ok(message("IGNORED"));
        }
        ("UPDATE", committed)
    } else if let Some(transaction) = root.get("TransactionUpdateLight") {
        if !transaction["update"].is_object() {
            return Ok(message("ERROR"));
        }
        ("UPDATE", &transaction["update"])
    } else {
        return Ok(message("IGNORED"));
    };
    let Some(tables) = update["tables"].as_array() else {
        return Ok(message("ERROR"));
    };
    let mut commands = Vec::new();
    let mut deleted = Vec::new();
    let mut desired_row = Value::Null;
    let mut monitoring_row = Value::Null;
    let mut desired_deleted = false;
    let mut monitoring_deleted = false;
    for table in tables {
        let name = string(table, "table_name");
        if ![COMMAND_TABLE, DESIRED_TABLE, MONITORING_TABLE].contains(&name.as_str()) {
            continue;
        }
        let Some(updates) = table["updates"].as_array() else {
            continue;
        };
        for raw_update in updates {
            if !raw_update.is_object() {
                continue;
            }
            let update = raw_update
                .get("Uncompressed")
                .filter(|value| value.is_object())
                .unwrap_or(raw_update);
            for row in update["deletes"].as_array().into_iter().flatten() {
                let text = content(row).ok_or("invalid_deleted_command")?;
                if text.len() > 16 * 1024 {
                    return Err("deleted_command_size".into());
                }
                let row = parse(&text).map_err(|_| "invalid_deleted_command")?;
                let id = match &row {
                    Value::Array(values) => values.first().and_then(primitive_content),
                    Value::Object(values) => values.get("id").and_then(primitive_content),
                    _ => None,
                }
                .ok_or("invalid_deleted_command")?;
                if name == DESIRED_TABLE {
                    desired_deleted |= id == format!("{ticket}:{backend}");
                } else if name == MONITORING_TABLE {
                    monitoring_deleted |= id == format!("{ticket}:{backend}");
                } else {
                    deleted.push(id.to_owned());
                }
            }
            for row in update["inserts"].as_array().into_iter().flatten() {
                if commands.len() >= 128 {
                    return Err("command_update_capacity".into());
                }
                let Some(text) = content(row).filter(|text| text.len() <= 16 * 1024) else {
                    continue;
                };
                let Ok(row) = parse(&text) else {
                    continue;
                };
                if name == DESIRED_TABLE {
                    if let Some(row) = desired(row, ticket, backend)? {
                        desired_row = row;
                    }
                } else if name == MONITORING_TABLE {
                    if let Some(row) = monitoring(row, ticket, backend)? {
                        monitoring_row = row;
                    }
                } else if let Some(row) = command(row, ticket, backend, (seconds, nanos)) {
                    commands.push(row);
                }
            }
        }
    }
    Ok(
        json!({"kind":kind,"commands":commands,"deleted":deleted,"desired":desired_row,
        "desiredDeleted":desired_deleted,"monitoring":monitoring_row,"monitoringDeleted":monitoring_deleted,
        "requestId":if kind == "APPLIED" { Some(0) } else { None }}),
    )
}
