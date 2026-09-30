//! Existing supervisor restart state and weekly cleanup decisions.
//! Clock/calendar/AlarmManager calls remain Android platform adapters.
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
};
use serde_json::{Value, json};

fn restart(
    state: &str,
    record: bool,
    now: i64,
    initial: i32,
    maximum: i32,
    window: i32,
    rapid_maximum: i32,
) -> Result<Value, String> {
    let mut start = now;
    let mut count = 0i32;
    let mut backoff = initial;
    if record && !state.is_empty() {
        let value: Value = serde_json::from_str(state).map_err(|_| "invalid backoff state")?;
        start = value["windowStart"]
            .as_i64()
            .ok_or("invalid backoff window")?;
        count = value["rapidCount"]
            .as_i64()
            .and_then(|n| i32::try_from(n).ok())
            .ok_or("invalid backoff count")?;
        backoff = value["currentBackoff"]
            .as_i64()
            .and_then(|n| i32::try_from(n).ok())
            .ok_or("invalid backoff delay")?;
        if now.wrapping_sub(start) > i64::from(window) {
            start = now;
            count = 0;
            backoff = initial;
        }
    }
    let mut delay = 0;
    let mut crash = false;
    if record {
        count = count.wrapping_add(1);
        crash = count > rapid_maximum;
        delay = if crash { 120 } else { backoff };
        if !crash {
            backoff = backoff.wrapping_mul(2).min(maximum);
        }
    }
    Ok(
        json!({"windowStart": start, "rapidCount": count, "currentBackoff": backoff,
        "decision": {"crashLoop": crash, "sleepSeconds": delay, "rapidCount": count}}),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_supervisor_NativeSupervisor_backoff(
    env: JNIEnv,
    _class: JClass,
    state: JString,
    record: jboolean,
    now: jlong,
    initial: jint,
    maximum: jint,
    window: jint,
    rapid_maximum: jint,
) -> jstring {
    crate::jni::bridge(env, "java/lang/IllegalStateException", |env| {
        restart(
            &crate::jni::text(env, &state)?,
            record != 0,
            now,
            initial,
            maximum,
            window,
            rapid_maximum,
        )
        .map(|v| Some(v.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_NativeCleanupSchedule_daysAhead(
    _env: JNIEnv,
    _class: JClass,
    current: jint,
    target: jint,
) -> jlong {
    i64::from((target.wrapping_sub(current)).rem_euclid(7))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_NativeCleanupSchedule_nextWeek(
    _env: JNIEnv,
    _class: JClass,
    now: jlong,
    now_nanos: jint,
    candidate: jlong,
    candidate_nanos: jint,
) -> jboolean {
    u8::from((candidate, candidate_nanos) <= (now, now_nanos))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_NativeCleanupSchedule_exactAlarm(
    _env: JNIEnv,
    _class: JClass,
    sdk: jint,
    granted: jboolean,
) -> jboolean {
    u8::from(sdk < 31 || granted != 0)
}
