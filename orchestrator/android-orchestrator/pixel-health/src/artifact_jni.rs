use crate::jni::{bridge, text};
use ::jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::jstring,
};
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_runtimeinstaller_NativeArtifact_decide(
    env: JNIEnv,
    _class: JClass,
    operation: JString,
    payload: JString,
) -> jstring {
    bridge(env, "java/lang/IllegalStateException", |env| {
        crate::artifact::decide(&text(env, &operation)?, &text(env, &payload)?)
            .map(|v| Some(v.to_string()))
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_lv_jolkins_pixelorchestrator_runtimeinstaller_NativeArtifact_sha256(
    env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    bridge(env, "java/io/IOException", |env| {
        let path = text(env, &path)?;
        match crate::artifact::sha256(&path) {
            Ok(value) => Ok(Some(value)),
            Err(error) => {
                let exception = match error.kind() {
                    std::io::ErrorKind::NotFound => "java/nio/file/NoSuchFileException",
                    std::io::ErrorKind::PermissionDenied => "java/nio/file/AccessDeniedException",
                    _ => "java/io/IOException",
                };
                let message = if matches!(
                    error.kind(),
                    std::io::ErrorKind::NotFound | std::io::ErrorKind::PermissionDenied
                ) {
                    path
                } else {
                    format!("artifact hash read failed: {}", error.kind())
                };
                env.throw_new(exception, message)
                    .map_err(|_| "unable to report artifact read failure")?;
                Ok(None)
            }
        }
    })
}
