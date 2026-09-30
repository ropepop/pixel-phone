//! Monotonic capture demand/cadence/cleanup policy; Android owns helper and stream effects.
use serde_json::{Value, json};

pub(crate) fn cadence(
    operation: i32,
    mut state: [i64; 4],
    now: i64,
    valid_until: i64,
    proof: bool,
) -> Result<[i64; 6], String> {
    let mut answer = 0;
    let mut error = 0;
    if matches!(operation, 0 | 1 | 3 | 5)
        && !(operation == 1 && (valid_until <= 0 || now <= 0))
        && state[2] != 0
        && now > state[3]
    {
        state[2] = 0;
        state[3] = 0;
    }
    match operation {
        0 => {
            answer = if state[1] != 0 && state[2] == 0 && !proof {
                -1
            } else {
                state[0].wrapping_sub(now).max(0)
            };
        }
        1 => {
            if valid_until > 0 && now > 0 {
                state[1] = 1;
                if valid_until >= now {
                    answer = i64::from(state[2] == 0);
                    state[2] = 1;
                    state[3] = state[3].max(valid_until);
                }
            }
        }
        2 => {
            state[1] = 1;
            state[2] = 0;
            state[3] = 0;
        }
        3 => {
            if state[1] != 0 && state[2] != 0 {
                state[2] = 0;
                state[3] = 0;
                answer = 1;
            }
        }
        4 => {
            state[0] = state[0].max(now.wrapping_add(1_000));
        }
        5 => {
            if state[1] != 0 && state[2] == 0 && !proof {
                error = 1;
            } else if now < state[0] {
                error = 2;
            } else {
                if state[1] != 0 && state[2] != 0 {
                    state[2] = 0;
                    state[3] = 0;
                }
                state[0] = now.wrapping_add(1_000);
            }
        }
        _ => return Err("unknown capture cadence operation".into()),
    }
    Ok([state[0], state[1], state[2], state[3], answer, error])
}

pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let args: Value = if operation == "parse_demand" {
        match crate::ticket_command::parse(payload) {
            Ok(value) => value,
            Err(()) => return Ok(Value::Null),
        }
    } else {
        serde_json::from_str(payload).map_err(|_| "invalid capture policy input")?
    };
    let number = |key: &str| {
        args[key]
            .as_i64()
            .ok_or_else(|| format!("missing capture {key}"))
    };
    match operation {
        "parse_demand" => {
            let Some(object) = args.as_object() else {
                return Ok(Value::Null);
            };
            let fields = ["type", "version", "streamEpoch", "generation", "ttlMillis"];
            if object.len() != fields.len()
                || fields.iter().any(|key| !object.contains_key(*key))
                || args["type"] != "S:capture_demand"
            {
                return Ok(Value::Null);
            }
            let positive = |key: &str| {
                args[key]
                    .as_str()
                    .filter(|value| value.starts_with("L:"))
                    .and_then(|_| crate::ticket_command::integer_long(&args[key]))
                    .filter(|v| (1..=9_007_199_254_740_991).contains(v))
            };
            let (Some(epoch), Some(generation)) = (positive("streamEpoch"), positive("generation"))
            else {
                return Ok(Value::Null);
            };
            if positive("version") != Some(1) || positive("ttlMillis") != Some(2_500) {
                return Ok(Value::Null);
            }
            Ok(json!({"streamEpoch":epoch,"generation":generation,"ttlMillis":2_500}))
        }
        "admit_demand" => {
            let current = number("currentEpoch")?;
            let generation = number("generation")?;
            let received = number("received")?;
            let valid = received.wrapping_add(number("ttlMillis")?);
            let answer = if current <= 0 || number("epoch")? != current {
                "WRONG_EPOCH"
            } else if generation <= number("lastGeneration")? {
                "NON_MONOTONIC_GENERATION"
            } else if received <= 0 || valid < received || number("now")? > valid {
                "EXPIRED"
            } else {
                "ACCEPTED"
            };
            Ok(json!({"answer":answer,"validUntil":valid}))
        }
        "continue_encoder" => {
            let active = args["active"] == true;
            let gated = args["gated"] == true;
            let expected = args["expected"] == true;
            let wait = number("wait")?;
            let answer = if active
                && gated
                && (!expected || args["expectedAge"].as_i64().is_some_and(|age| age < wait))
                || args["firstPending"] == true
            {
                true
            } else if !active && !matches!(args["state"].as_str(), Some("starting" | "restarting"))
            {
                false
            } else if args["frameAge"]
                .as_i64()
                .is_some_and(|age| age <= number("liveMax").unwrap_or(0))
            {
                true
            } else {
                args["startAge"].as_i64().is_none_or(|age| age < wait)
            };
            Ok(json!(answer))
        }
        "waiting_first" => {
            let Some(start) = args["startAge"].as_i64() else {
                return Ok(json!(false));
            };
            if start < 0
                || number("grace")? <= 0
                || start >= number("grace")?
                || (args["active"] != true
                    && !matches!(args["state"].as_str(), Some("starting" | "restarting")))
            {
                return Ok(json!(false));
            }
            let useful = args["frameAge"]
                .as_i64()
                .is_some_and(|age| age >= 0 && age <= start)
                && args["sourceAge"]
                    .as_i64()
                    .is_some_and(|age| age <= number("usefulMax").unwrap_or(0));
            Ok(json!(!useful))
        }
        "cleanup" => {
            let expected = args["expectedGeneration"].as_i64();
            let answer = if args["streamActive"] != true
                || number("clientCount")? != 0
                || expected.is_some_and(|generation| {
                    generation <= 0 || Some(generation) != args["currentGeneration"].as_i64()
                }) {
                "DROP"
            } else if ["start", "control", "action", "reauth"]
                .iter()
                .any(|key| args[key] == true)
            {
                "RETRY"
            } else {
                "STOP"
            };
            Ok(json!(answer))
        }
        _ => Err("unknown capture policy operation".into()),
    }
}

/// Encoder priming state. The Java adapter retains only the selected platform frame reference.
pub(crate) fn startup(
    operation: i32,
    mut state: [i64; 9],
    now: i64,
    vcl: bool,
    key: bool,
    valid_frame: bool,
) -> Result<[i64; 11], String> {
    let wait = |s: &[i64; 9]| {
        if s[8] != 0 || s[4] != 0 || s[1] >= s[0] || s[3] < 0 {
            0
        } else {
            s[3].wrapping_add(100).wrapping_sub(now).max(0)
        }
    };
    let another = |s: &[i64; 9]| s[8] == 0 && s[4] == 0 && s[1] < s[0];
    let mut answer = 0;
    let mut error = 0;
    match operation {
        0 => {
            state = [
                if now > 0 { now.min(3) } else { 3 },
                0,
                0,
                -1,
                0,
                0,
                0,
                0,
                0,
            ];
        }
        1 => answer = i64::from(another(&state) && wait(&state) == 0),
        2 => answer = i64::from(another(&state)),
        3 => answer = wait(&state),
        4 => {
            if !another(&state) || wait(&state) != 0 {
                error = 1;
            } else {
                state[1] = i64::from((state[1] as i32).wrapping_add(1));
                state[3] = now;
            }
        }
        5 => {
            if state[8] == 0 && vcl {
                state[2] = i64::from((state[2] as i32).wrapping_add(1));
                if key && state[4] == 0 {
                    state[4] = 1;
                } else {
                    answer = if state[6] != 0 && key { 1 } else { 2 };
                }
            }
        }
        6 => {
            if state[8] != 0 || state[4] == 0 || state[6] != 0 {
                error = 2;
            } else {
                state[6] = 1;
                state[7] = 0;
            }
        }
        7 => {
            if state[6] == 0 || !valid_frame {
                error = 3;
            }
        }
        8 => {
            answer = state[6];
            state[6] = 0;
        }
        9 => state[7] = 1,
        10 => {
            state[6] = 0;
            state[8] = 1;
        }
        11 => state[5] = 1,
        _ => return Err("unknown encoder startup operation".into()),
    }
    Ok([
        state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7], state[8],
        answer, error,
    ])
}
pub(crate) fn codec_input(stage: Option<[i64; 5]>, tail: Option<[i64; 5]>, count: i32) -> i32 {
    let Some(stage) = stage else {
        return 1;
    };
    if stage[0] <= 0 || stage[1] <= 0 {
        1
    } else if stage[2] <= 0 || stage[3] < stage[2] || stage[4] < stage[3] {
        2
    } else if tail.is_some_and(|tail| stage[4] < tail[4]) {
        3
    } else if count >= 8 {
        4
    } else {
        0
    }
}

pub(crate) fn visible(pixels: &[i32]) -> bool {
    if pixels.is_empty() {
        return false;
    }
    let mut bright = 0;
    let mut dark = 0;
    let mut min = 255;
    let mut max = 0;
    let mut sum = 0_i64;
    for pixel in pixels {
        let red = (pixel >> 16) & 255;
        let green = (pixel >> 8) & 255;
        let blue = pixel & 255;
        let luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        min = min.min(luminance);
        max = max.max(luminance);
        sum += i64::from(luminance);
        if luminance >= 180 {
            bright += 1;
        }
        if luminance <= 60 {
            dark += 1;
        }
    }
    max - min >= 35
        && bright >= 8
        && f64::from(dark) / (pixels.len() as f64) >= 0.02
        && (sum as f64) / (pixels.len() as f64) >= 35.0
}
