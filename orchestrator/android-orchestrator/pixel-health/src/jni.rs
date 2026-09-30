use ::jni::{
    JNIEnv,
    objects::{JCharArray, JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
};
use std::panic::{AssertUnwindSafe, catch_unwind};

pub(crate) fn text(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|_| "unable to read health bridge input".into())
}

pub(crate) fn bridge(
    mut env: JNIEnv,
    exception: &str,
    operation: impl FnOnce(&mut JNIEnv) -> Result<Option<String>, String>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| operation(&mut env)))
        .unwrap_or_else(|_| Err("native operation failed".into()));
    match result {
        Ok(None) => std::ptr::null_mut(),
        Ok(Some(output)) => match env.new_string(output) {
            Ok(output) => output.into_raw(),
            Err(_) => {
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    "unable to encode native bridge output",
                );
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            // Errors contain bounded operational descriptions, never stored
            // values or probe contents. Java exceptions keep failures visible.
            let _ = env.throw_new(exception, error);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_health_NativeHealth_buildProbe(
    env: JNIEnv,
    _class: JClass,
    config: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::build_probe(&text(env, &config)?).map(Some)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_health_NativeHealth_interpretProbe(
    env: JNIEnv,
    _class: JClass,
    config: JString,
    ok: jboolean,
    stdout: JString,
    now_epoch: jlong,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::interpret_probe(
            &text(env, &config)?,
            ok != 0,
            &text(env, &stdout)?,
            now_epoch,
        )
        .map(|snapshot| Some(snapshot.to_string()))
    })
}

// Files.writeString rejected malformed UTF-16. Decode strictly for paths and
// stored bodies so the native move cannot silently change their bytes.
fn store_text(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    let chars = env
        .call_method(value, "toCharArray", "()[C", &[])
        .and_then(|value| value.l())
        .map(JCharArray::from)
        .map_err(|_| "unable to read store input")?;
    let length = env
        .get_array_length(&chars)
        .map_err(|_| "unable to read store input")?;
    let mut units = vec![0; length as usize];
    env.get_char_array_region(&chars, 0, &mut units)
        .map_err(|_| "unable to read store input")?;
    String::from_utf16(&units).map_err(|_| "store input contains malformed UTF-16".into())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketCheckpoint_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalArgumentException", |env| {
        crate::ticket_checkpoint::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketCapture_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::ticket_capture::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_telemetry_NativeTelemetry_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalArgumentException", |env| {
        crate::telemetry::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMonitoring_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::ticket_monitoring::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketCommand_policy(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::ticket_command_state::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketCommand_decode(
    env: JNIEnv,
    _class: JClass,
    raw: JString,
    ticket: JString,
    backend: JString,
    seconds: jlong,
    nanos: jint,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        // Refuse oversized Java input before making its native text copy.
        let length = env
            .call_method(&raw, "length", "()I", &[])
            .and_then(|value| value.i())
            .map_err(|_| "unable to read command message length")?;
        if length as usize > crate::ticket_command::MAX_MESSAGE_CHARS {
            return Ok(Some("{\"kind\":\"ERROR\"}".into()));
        }
        crate::ticket_command::decode(
            &text(env, &raw)?,
            &text(env, &ticket)?,
            &text(env, &backend)?,
            seconds,
            nanos as u32,
        )
        .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_supervisor_NativeSupervisor_transition(
    env: JNIEnv,
    _class: JClass,
    state: JString,
    change: JString,
    now: jlong,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::supervisor::transition(&text(env, &state)?, &text(env, &change)?, now)
            .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_supervisor_NativeSupervisor_network(
    env: JNIEnv,
    _class: JClass,
    previous: JString,
    state: JString,
    snapshot: JString,
    config: JString,
    now: jlong,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::supervisor::network(
            &text(env, &previous)?,
            &text(env, &state)?,
            &text(env, &snapshot)?,
            &text(env, &config)?,
            now,
        )
        .map(|value| Some(value.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_coreconfig_NativeStore_readUtf8OrNull(
    env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        Ok(crate::store::read(&store_text(env, &path)?))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_coreconfig_NativeStore_writeAtomic(
    env: JNIEnv,
    _class: JClass,
    path: JString,
    body: JString,
) {
    bridge(env, "java/io/IOException", |env| {
        crate::store::write(&store_text(env, &path)?, &store_text(env, &body)?)
            .map_err(|error| format!("store write failed: {}", error.kind()))?;
        Ok(None)
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_coreconfig_NativeStore_legacyRemoteAliases(
    env: JNIEnv,
    _class: JClass,
    remote: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::store::legacy_aliases(&text(env, &remote)?).map(Some)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_coreconfig_NativeStore_redactToken(
    env: JNIEnv,
    _class: JClass,
    token: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        let token = text(env, &token)?;
        Ok(Some(if crate::store::secret_is_set(&token) {
            "***redacted***".into()
        } else {
            token
        }))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketControl_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::ticket_control::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|v| Some(v.to_string()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketAction_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalArgumentException", |env| {
        crate::ticket_action_policy::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|v| Some(v.to_string()))
    })
}
