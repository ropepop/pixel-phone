use ::jni::{
    JNIEnv,
    objects::{JClass, JIntArray},
    sys::{jint, jlongArray},
};
use std::panic::{AssertUnwindSafe, catch_unwind};

#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_recognizeDates(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
) -> jlongArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jlongArray, String> {
        let mut values = vec![];
        if !pixels.is_null() {
            let size = env
                .get_array_length(&pixels)
                .map_err(|_| "invalid visual pixels")?;
            if crate::visual_date::valid_size(size as usize, width, height).is_some() {
                let mut input = vec![0; size as usize];
                env.get_int_array_region(&pixels, 0, &mut input)
                    .map_err(|_| "unable to read visual pixels")?;
                for range in crate::visual_date::recognize(&input, width, height) {
                    values.extend([range.from, range.until, range.center_y as i64]);
                }
            }
        }
        let array = env
            .new_long_array(values.len() as i32)
            .map_err(|_| "unable to allocate visual result")?;
        env.set_long_array_region(&array, 0, &values)
            .map_err(|_| "unable to write visual result")?;
        Ok(array.into_raw())
    }))
    .unwrap_or_else(|_| Err("native visual recognition failed".into()));
    match result {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}

fn bounds<'a>(
    env: &mut JNIEnv<'a>,
    value: Option<crate::visual_action::Bounds>,
) -> ::jni::errors::Result<::jni::objects::JObject<'a>> {
    use ::jni::objects::{JObject, JValue};
    match value {
        None => Ok(JObject::null()),
        Some(b) => env.new_object(
            "lv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds",
            "(IIII)V",
            &[
                JValue::Int(b.left),
                JValue::Int(b.top),
                JValue::Int(b.right),
                JValue::Int(b.bottom),
            ],
        ),
    }
}
fn action_pixels(env: &mut JNIEnv, pixels: &JIntArray) -> ::jni::errors::Result<Vec<i32>> {
    if pixels.is_null() {
        return Ok(vec![]);
    }
    let len = env.get_array_length(pixels)?;
    if len != 192 * 288 && len != 384 * 576 {
        return Ok(vec![]);
    }
    let mut p = vec![0; len as usize];
    env.get_int_array_region(pixels, 0, &mut p)?;
    Ok(p)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_selectedNavigation(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
) -> ::jni::sys::jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> ::jni::errors::Result<_> {
        let pixels = action_pixels(&mut env, &pixels)?;
        Ok(env
            .new_string(crate::visual_action::selected_navigation(&pixels))?
            .into_raw())
    }));
    match result {
        Ok(Ok(value)) => value,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "native navigation recognition failed",
            );
            std::ptr::null_mut()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_classifyAction(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    resolve_list: ::jni::sys::jboolean,
    today: ::jni::sys::jlong,
) -> ::jni::sys::jobject {
    use ::jni::objects::{JObject, JValue};
    let result = catch_unwind(AssertUnwindSafe(|| -> ::jni::errors::Result<_> {
        let pixels = action_pixels(&mut env, &pixels)?;
        let result = crate::visual_action::classify(&pixels, resolve_list != 0, today);
        let cards = env.new_object("java/util/ArrayList", "()V", &[])?;
        for card in result.cards {
            env.with_local_frame(24,|env|->::jni::errors::Result<()> {
                let b=bounds(env,Some(card.bounds))?;let reg=bounds(env,card.registration)?;let active=bounds(env,card.activated)?;
                let anchor=env.call_static_method("lv/jolkins/pixelorchestrator/app/ticket/TicketVisualDateGlyphRecognizer","anchorForEpochDays","(JJ)Ljava/lang/String;",&[JValue::Long(card.date.from),JValue::Long(card.date.until)])?.l()?;
                let value=env.new_object("lv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Card","(Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Ljava/lang/String;Z)V",&[JValue::Object(&b),JValue::Object(&reg),JValue::Object(&active),JValue::Object(&anchor),JValue::Bool(u8::from(card.latest))])?;
                env.call_method(&cards,"add","(Ljava/lang/Object;)Z",&[JValue::Object(&value)])?;
                Ok(())
            })?;
        }
        let anchor = if result.detail_identity {
            let geometry =
                crate::visual_action::geometry(&pixels).expect("validated action geometry");
            let array = env.new_int_array(geometry.len() as i32)?;
            env.set_int_array_region(&array, 0, &geometry)?;
            env.call_static_method(
                "lv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier",
                "detailVisualAnchor",
                "([I)Ljava/lang/String;",
                &[JValue::Object(&JObject::from(array))],
            )?
            .l()?
        } else {
            JObject::from(env.new_string("")?)
        };
        let state = env.new_string(result.state)?;
        let state = env
            .call_method(&state, "intern", "()Ljava/lang/String;", &[])?
            .l()?;
        let slider = bounds(&mut env, result.slider)?;
        let control = bounds(&mut env, result.control)?;
        let back = bounds(&mut env, result.back)?;
        let tickets = bounds(&mut env, result.tickets)?;
        let time = bounds(&mut env, result.time)?;
        Ok(env.new_object("lv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Result","(Ljava/lang/String;Ljava/lang/String;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Llv/jolkins/pixelorchestrator/app/ticket/TicketVisualActionClassifier$Bounds;Ljava/util/List;)V",&[JValue::Object(&state),JValue::Object(&anchor),JValue::Object(&slider),JValue::Object(&control),JValue::Object(&back),JValue::Object(&tickets),JValue::Object(&time),JValue::Object(&cards)])?.into_raw())
    }));
    match result {
        Ok(Ok(value)) => value,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "native action recognition failed",
            );
            std::ptr::null_mut()
        }
    }
}

fn read_control_pixels(
    env: &mut JNIEnv,
    pixels: &JIntArray,
    expected: Option<usize>,
) -> ::jni::errors::Result<Vec<i32>> {
    if pixels.is_null() {
        return Ok(vec![]);
    }
    let len = env.get_array_length(pixels)? as usize;
    if expected != Some(len) {
        return Ok(vec![]);
    }
    let mut values = vec![0; len];
    env.get_int_array_region(pixels, 0, &mut values)?;
    Ok(values)
}
macro_rules! control_string {
    ($jni:ident,$function:ident,$size:expr) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $jni(
            mut env: JNIEnv,
            _class: JClass,
            pixels: JIntArray,
        ) -> ::jni::sys::jstring {
            let result = catch_unwind(AssertUnwindSafe(|| -> ::jni::errors::Result<_> {
                let p = read_control_pixels(&mut env, &pixels, Some($size))?;
                Ok(env
                    .new_string(crate::visual_control::$function(&p))?
                    .into_raw())
            }));
            match result {
                Ok(Ok(value)) => value,
                _ => {
                    let _ = env.throw_new(
                        "java/lang/IllegalStateException",
                        "native control recognition failed",
                    );
                    std::ptr::null_mut()
                }
            }
        }
    };
}
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlClassify,
    classify,
    48 * 72
);
control_string!(Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlClassifyForActivatedTicket,classify_for_activated_ticket,48*72);
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlClassifyForCleanup,
    classify_for_cleanup,
    48 * 72
);
control_string!(Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlClassifyForCleanupHighResolution,classify_for_cleanup_high_resolution,96*144);
control_string!(Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlGeneratedResultCloseBounds,generated_result_close_bounds,48*72);
control_string!(Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlGeneratedResultCloseBoundsHighResolution,generated_result_close_bounds_high_resolution,96*144);
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlRegistrationSliderBounds,
    registration_slider_bounds,
    48 * 72
);
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlClassifySubmitLayout,
    classify_submit_layout,
    96 * 144
);
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlSubmitInputBounds,
    submit_input_bounds,
    96 * 144
);
control_string!(
    Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlSubmitButtonBounds,
    submit_button_bounds,
    96 * 144
);

fn signature_bytes(
    mut env: JNIEnv,
    pixels: JIntArray,
    width: jint,
    height: jint,
    kind: u8,
) -> ::jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> ::jni::errors::Result<_> {
        let expected = if width >= 48 && height >= 72 {
            (width as usize).checked_mul(height as usize)
        } else {
            None
        };
        let p = read_control_pixels(&mut env, &pixels, expected)?;
        let value = match kind {
            0 => crate::visual_control::code_signature_pixels(&p),
            1 => crate::visual_control::high_res_code_signature_pixels(&p),
            _ => crate::visual_control::static_signature_pixels(&p, width, height),
        };
        match value {
            Some(bytes) => Ok(env.byte_array_from_slice(&bytes)?.into_raw()),
            None => Ok(std::ptr::null_mut()),
        }
    }));
    match result {
        Ok(Ok(value)) => value,
        _ => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "native visual signature failed",
            );
            std::ptr::null_mut()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlCodeSignaturePixels(
    env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
) -> ::jni::sys::jbyteArray {
    signature_bytes(env, pixels, 48, 72, 0)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlHighResCodeSignaturePixels(
    env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
) -> ::jni::sys::jbyteArray {
    signature_bytes(env, pixels, 96, 144, 1)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_app_ticket_NativeTicketMedia_controlStaticSignaturePixels(
    env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
) -> ::jni::sys::jbyteArray {
    signature_bytes(env, pixels, width, height, 2)
}
