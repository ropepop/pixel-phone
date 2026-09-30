//! Brightness verification policy; Android retains sysfs/settings mutations and raw-touch arbitration.
use serde_json::{Value, json};
fn integer(value: &Value, key: &str) -> Option<i32> {
    value[key].as_i64().and_then(|v| i32::try_from(v).ok())
}
fn display(value: &Value) -> Option<f32> {
    integer(value, "displayBits").map(|v| f32::from_bits(v as u32))
}
fn panel(value: &Value) -> Option<i32> {
    integer(value, "panelActualBrightness").or_else(|| integer(value, "panelBrightness"))
}
fn has_panel(value: &Value) -> bool {
    integer(value, "panelMaxBrightness").is_some() && panel(value).is_some()
}
fn visible_panel(value: &Value) -> bool {
    panel(value).is_some_and(|v| v > 2)
}
fn system_value(percent: i32) -> i32 {
    ((percent.clamp(0, 100) as f64 / 100.0) * 255.0) as i32
}
fn system_percent(value: i32) -> i32 {
    ((value.clamp(0, 255) as f64 / 255.0) * 100.0) as i32
}
fn round(value: f64) -> Result<i32, String> {
    if value.is_nan() {
        Err("Cannot round NaN value.".into())
    } else {
        Ok((value + 0.5).floor() as i32)
    }
}
fn panel_value(percent: i32, max: i32) -> i32 {
    let percent = percent.clamp(0, 100);
    let max = max.max(1);
    let target = round(f64::from(max.wrapping_mul(percent)) / 100.0)
        .unwrap_or(0)
        .clamp(0, max);
    if percent > 0 && target == 0 {
        1
    } else {
        target
    }
}
fn panel_matches(value: &Value, target: i32) -> bool {
    let Some(max) = integer(value, "panelMaxBrightness").filter(|v| *v > 0) else {
        return false;
    };
    let Some(current) = panel(value) else {
        return false;
    };
    if target == 0 {
        return current == 0;
    }
    if current
        .wrapping_sub(panel_value(target, max))
        .wrapping_abs()
        <= 2
    {
        return true;
    }
    ((current as f32 / max as f32) * 100.0 - target as f32).abs() <= 1.0
}
fn target_matches(value: &Value, target: i32, panel_only: bool) -> bool {
    if panel_only {
        return panel_matches(value, target);
    }
    if target > 0 && has_panel(value) && !panel_matches(value, target) {
        return false;
    }
    if integer(value, "mode").is_some_and(|mode| mode != 0) {
        return false;
    }
    if target > 0 && display(value).is_some_and(|v| v <= 0.5) {
        return false;
    }
    if integer(value, "value") == Some(system_value(target)) {
        return true;
    }
    if let Some(display) = display(value) {
        return (display - target as f32).abs() <= 0.5;
    }
    integer(value, "value").is_none()
}
fn restored_matches(value: &Value, expected: &Value, panel_only: bool) -> bool {
    let expected_panel =
        integer(expected, "panelBrightness").or_else(|| integer(expected, "panelActualBrightness"));
    if let Some(expected_panel) = expected_panel {
        let actual = panel(value);
        if actual.is_some_and(|actual| actual.wrapping_sub(expected_panel).wrapping_abs() <= 2) {
            if panel_only {
                return true;
            }
        } else if panel_only || actual.is_some() {
            return false;
        }
    } else if panel_only {
        return !has_panel(value) || visible_panel(value);
    }
    if !panel_only
        && integer(expected, "mode") != Some(1)
        && has_panel(value)
        && !visible_panel(value)
    {
        return false;
    }
    let expects_visible =
        display(expected).is_some_and(|v| v > 0.5) || integer(expected, "value").unwrap_or(0) > 0;
    if !panel_only && expects_visible && display(value).is_some_and(|v| v <= 0.5) {
        return false;
    }
    if let (Some(actual), Some(expected)) = (integer(value, "mode"), integer(expected, "mode"))
        && actual != expected
    {
        return false;
    }
    match integer(expected, "mode") {
        Some(1) => true,
        Some(0) | None => {
            let Some(expected) = integer(expected, "value") else {
                return true;
            };
            if integer(value, "value") == Some(expected) {
                true
            } else if let Some(display) = display(value) {
                (display - system_percent(expected) as f32).abs() <= 0.5
            } else {
                true
            }
        }
        _ => true,
    }
}
pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let args: Value = serde_json::from_str(payload).map_err(|_| "invalid brightness input")?;
    let state = &args["state"];
    let expected = &args["expected"];
    let target = integer(&args, "target").unwrap_or(0);
    let panel_only = args["panelOnly"].as_bool().unwrap_or(false);
    Ok(match operation {
        "system_value" => json!(system_value(target)),
        "system_percent" => json!(system_percent(target)),
        "panel_value" => json!(panel_value(
            target,
            integer(state, "panelMaxBrightness").unwrap_or(0)
        )),
        "panel_matches" => json!(panel_matches(state, target)),
        "target_matches" => json!(target_matches(state, target, panel_only)),
        "restored_matches" => json!(restored_matches(state, expected, panel_only)),
        "has_panel" => json!(has_panel(state)),
        "visible_panel" => json!(visible_panel(state)),
        "panel_sleep" => json!(
            integer(state, "panelBacklightPower") == Some(4)
                || integer(state, "value").is_some_and(|v| v <= 0)
                || display(state).is_some_and(|v| v <= 0.5)
                || panel(state).is_some_and(|v| v <= 2)
        ),
        "fallback_percent" => json!(
            if let Some(display) = display(state) {
                round(f64::from(display))?
            } else if let Some(value) = integer(state, "value") {
                system_percent(value)
            } else {
                20
            }
            .clamp(20, 100)
        ),
        "android_unchanged" => json!(
            state["mode"] == expected["mode"]
                && state["value"] == expected["value"]
                && display(state).unwrap_or(0.0) > 0.5
        ),
        "remote_fallback" => {
            let mut result = state.clone();
            if !expected.is_null() {
                if result["displayBits"].is_null() {
                    result["displayBits"] = expected["displayBits"].clone();
                }
                if visible_panel(expected) {
                    for key in [
                        "panelPath",
                        "panelBrightness",
                        "panelActualBrightness",
                        "panelMaxBrightness",
                    ] {
                        if result[key].is_null() {
                            result[key] = expected[key].clone();
                        }
                    }
                }
            }
            result
        }
        _ => return Err("unknown brightness operation".into()),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_phoneautomation_NativeTouchBrightness_decide(
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
