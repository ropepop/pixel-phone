use crate::media::{self, Assembler};
use ::jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JIntArray, JLongArray, JValue},
    sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jobject},
};
use std::panic::{AssertUnwindSafe, catch_unwind};

fn boundary<T: Default>(
    mut env: JNIEnv,
    exception: &str,
    operation: impl FnOnce(&mut JNIEnv) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| operation(&mut env)))
        .unwrap_or_else(|_| Err("native media operation failed".into()))
    {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new(exception, error);
            T::default()
        }
    }
}

fn metadata<const N: usize>(env: &mut JNIEnv, array: &JLongArray) -> Result<[i64; N], String> {
    if env
        .get_array_length(array)
        .map_err(|_| "invalid frame metadata")?
        != N as i32
    {
        return Err("invalid frame metadata".into());
    }
    let mut values = [0; N];
    env.get_long_array_region(array, 0, &mut values)
        .map_err(|_| "invalid frame metadata")?;
    Ok(values)
}

fn bytes(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(array)
        .map_err(|_| "invalid frame bytes".into())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_captureCadence(
    env: JNIEnv,
    _class: JClass,
    operation: jint,
    state: JLongArray,
    now: jlong,
    valid_until: jlong,
    proof: jboolean,
) -> jlongArray {
    boundary(env, "java/lang/IllegalStateException", |env| {
        let state = crate::ticket_capture::cadence(
            operation,
            metadata(env, &state)?,
            now,
            valid_until,
            proof != 0,
        )?;
        let output = env
            .new_long_array(state.len() as i32)
            .map_err(|_| "unable to allocate capture state")?;
        env.set_long_array_region(&output, 0, &state)
            .map_err(|_| "unable to encode capture state")?;
        Ok(output.into_raw())
    })
}

fn java_bytes(env: &mut JNIEnv, bytes: &[u8]) -> Result<jbyteArray, String> {
    env.byte_array_from_slice(bytes)
        .map(|value| value.into_raw())
        .map_err(|_| "unable to allocate frame bytes".into())
}

// The Java owner creates one handle, synchronizes every operation, and zeros it
// before close. No handle escapes that owner. Calls after close never cross JNI.
unsafe fn assembler<'a>(handle: jlong) -> Result<&'a mut Assembler, String> {
    if handle == 0 {
        return Err("assembler is closed".into());
    }
    Ok(unsafe { &mut *(handle as *mut Assembler) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_assemblerCreate(
    env: JNIEnv,
    _class: JClass,
) -> jlong {
    boundary(env, "java/lang/IllegalStateException", |_| {
        Ok(Box::into_raw(Box::new(Assembler::default())) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_assemblerClose(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    boundary(env, "java/lang/IllegalStateException", |_| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut Assembler));
            }
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_assemblerReset(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    boundary(env, "java/lang/IllegalStateException", |_| {
        unsafe { assembler(handle)? }.reset();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_assemblerOverflowed(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    boundary(env, "java/lang/IllegalStateException", |_| {
        Ok(u8::from(unsafe { assembler(handle)? }.consume_overflowed()))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_assemblerAccept(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    partial: jboolean,
    config: jboolean,
    key: jboolean,
) -> jobject {
    boundary(env, "java/lang/IllegalStateException", |env| {
        let data = if data.is_null() {
            vec![]
        } else if env
            .get_array_length(&data)
            .map_err(|_| "invalid frame bytes")? as usize
            > media::MAX_PAYLOAD
        {
            // Contents cannot be consumed above the contract limit. Bound the
            // native copy even if a malformed producer supplies a huge buffer.
            vec![0; media::MAX_PAYLOAD + 1]
        } else {
            bytes(env, &data)?
        };
        let Some(unit) =
            (unsafe { assembler(handle)? }).accept(&data, partial != 0, config != 0, key != 0)
        else {
            return Ok(std::ptr::null_mut());
        };
        let payload = env
            .byte_array_from_slice(&unit.payload)
            .map_err(|_| "unable to allocate frame bytes")?;
        env.new_object(
            "lv/jolkins/pixelorchestrator/app/ticket/TicketH264EncoderOutputAssembler$EmittedAccessUnit",
            "([BZZZZ)V",
            &[JValue::Object(&payload), JValue::Bool(unit.codec_config.into()), JValue::Bool(unit.key_frame.into()),
              JValue::Bool(unit.contains_vcl.into()), JValue::Bool(unit.idr_key_frame.into())],
        ).map(|value| value.into_raw()).map_err(|_| "unable to create access unit".into())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_thfHeader(
    env: JNIEnv,
    _class: JClass,
    key: jboolean,
    stages: JLongArray,
    payload_len: jint,
) -> jbyteArray {
    boundary(env, "java/io/IOException", |env| {
        let header = media::thf_header(key != 0, &metadata(env, &stages)?, payload_len as usize)?;
        java_bytes(env, &header)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_thfPayloadLength(
    env: JNIEnv,
    _class: JClass,
    header: JByteArray,
) -> jint {
    boundary(env, "java/io/IOException", |env| {
        Ok(media::parse_thf_header(&bytes(env, &header)?)?.2 as jint)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_thfRecord(
    env: JNIEnv,
    _class: JClass,
    header: JByteArray,
    payload: JByteArray,
) -> jobject {
    boundary(env, "java/io/IOException", |env| {
        let (key, stages, size) = media::parse_thf_header(&bytes(env, &header)?)?;
        if env
            .get_array_length(&payload)
            .map_err(|_| "missing THF1 record")? as usize
            != size
        {
            return Err("invalid THF1 payload length".into());
        }
        media::validate_thf(&stages, size)?;
        env.new_object(
            "lv/jolkins/pixelorchestrator/app/ticket/TicketH264FrameRecord",
            "(ZJJJJJJJ[B)V",
            &[
                JValue::Bool(key.into()),
                JValue::Long(stages[0]),
                JValue::Long(stages[1]),
                JValue::Long(stages[2]),
                JValue::Long(stages[3]),
                JValue::Long(stages[4]),
                JValue::Long(stages[5]),
                JValue::Long(stages[6]),
                JValue::Object(&payload),
            ],
        )
        .map(|value| value.into_raw())
        .map_err(|_| "unable to create THF1 record".into())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_tsfHeader(
    env: JNIEnv,
    _class: JClass,
    key: jboolean,
    stages: JLongArray,
    payload_len: jint,
) -> jbyteArray {
    boundary(env, "java/lang/IllegalArgumentException", |env| {
        let header = media::tsf_header(key != 0, &metadata(env, &stages)?, payload_len as usize)?;
        java_bytes(env, &header)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_encoderStartup(
    env: JNIEnv,
    _class: JClass,
    operation: jint,
    state: JLongArray,
    now: jlong,
    vcl: jboolean,
    key: jboolean,
    valid_frame: jboolean,
) -> jlongArray {
    boundary(env, "java/lang/IllegalStateException", |env| {
        let value = crate::ticket_capture::startup(
            operation,
            metadata::<9>(env, &state)?,
            now,
            vcl != 0,
            key != 0,
            valid_frame != 0,
        )?;
        let output = env
            .new_long_array(11)
            .map_err(|_| "unable to allocate startup state")?;
        env.set_long_array_region(&output, 0, &value)
            .map_err(|_| "unable to write startup state")?;
        Ok(output.into_raw())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_validateCodecInput(
    env: JNIEnv,
    _class: JClass,
    stage: JLongArray,
    tail: JLongArray,
    count: jint,
) -> jint {
    boundary(env, "java/lang/IllegalStateException", |env| {
        let optional = |env: &mut JNIEnv, value: &JLongArray| -> Result<Option<[i64; 5]>, String> {
            if env
                .get_array_length(value)
                .map_err(|_| "invalid codec input")?
                == 0
            {
                Ok(None)
            } else {
                metadata::<5>(env, value).map(Some)
            }
        };
        Ok(crate::ticket_capture::codec_input(
            optional(env, &stage)?,
            optional(env, &tail)?,
            count,
        ))
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_nextEncoderGeneration(
    _env: JNIEnv,
    _class: JClass,
    current: jlong,
    now: jlong,
) -> jlong {
    current.wrapping_add(1).max(now)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_captureLooksVisible(
    env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
) -> jboolean {
    boundary(env, "java/lang/IllegalStateException", |env| {
        if pixels.is_null() {
            return Ok(0);
        }
        let length = env
            .get_array_length(&pixels)
            .map_err(|_| "invalid visibility probe")?;
        let mut values = vec![0; length as usize];
        env.get_int_array_region(&pixels, 0, &mut values)
            .map_err(|_| "unable to read visibility probe")?;
        Ok(u8::from(crate::ticket_capture::visible(&values)))
    })
}
