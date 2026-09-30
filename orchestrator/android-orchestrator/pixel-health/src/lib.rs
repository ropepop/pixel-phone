#![recursion_limit = "512"]
mod artifact;
mod artifact_jni;
mod cleanup_policy;
mod decisions;
mod jni;
mod maintenance;
mod media;
mod media_jni;
mod parser;
mod probe;
mod redeploy_policy;
mod store;
mod supervisor;
mod telemetry;
mod ticket_action_policy;
mod ticket_capture;
mod ticket_checkpoint;
mod ticket_command;
mod ticket_command_state;
mod ticket_control;
mod ticket_monitoring;
mod touch_brightness;
mod visual_action;
mod visual_control;
mod visual_date;
mod visual_jni;

use serde_json::Value;

pub struct Config(Value);

impl Config {
    pub fn parse(json: &str) -> Result<Self, String> {
        let value: Value =
            serde_json::from_str(json).map_err(|_| "invalid health configuration JSON")?;
        if !value.is_object() || !value["modules"].is_object() {
            return Err("health configuration must contain a modules object".into());
        }
        Ok(Self(value))
    }
    fn value(&self, path: &str) -> &Value {
        path.split('.').fold(&self.0, |value, key| &value[key])
    }
    pub fn s(&self, path: &str) -> Result<&str, String> {
        self.value(path)
            .as_str()
            .ok_or_else(|| format!("health configuration requires string {path}"))
    }
    pub fn b(&self, path: &str) -> Result<bool, String> {
        self.value(path)
            .as_bool()
            .ok_or_else(|| format!("health configuration requires boolean {path}"))
    }
    pub fn i(&self, path: &str) -> Result<i64, String> {
        self.value(path)
            .as_i64()
            .filter(|v| i32::try_from(*v).is_ok())
            .ok_or_else(|| format!("health configuration requires integer {path}"))
    }
    pub fn enabled(&self, module: &str) -> Result<bool, String> {
        match self.0["modules"].get(module) {
            None => Ok(true),
            Some(value) => value["enabled"]
                .as_bool()
                .ok_or_else(|| format!("health module {module} requires enabled")),
        }
    }
    pub fn mode(&self) -> Result<String, String> {
        let mode = trim(self.s("remote.dohEndpointMode")?).to_lowercase();
        Ok(if mode.is_empty() {
            "native".into()
        } else {
            mode
        })
    }
}

// Kotlin Char.isWhitespace includes Java space characters and the four ASCII
// information separators, but excludes NEXT LINE. Keep parsing compatible.
pub(crate) fn whitespace(c: char) -> bool {
    (c.is_whitespace() && c != '\u{0085}') || ('\u{001c}'..='\u{001f}').contains(&c)
}
pub(crate) fn trim(s: &str) -> &str {
    s.trim_matches(whitespace)
}

pub fn build_probe(config_json: &str) -> Result<String, String> {
    probe::build(&Config::parse(config_json)?)
}

pub fn interpret_probe(
    config_json: &str,
    ok: bool,
    stdout: &str,
    now_epoch: i64,
) -> Result<Value, String> {
    let mut snapshot = decisions::interpret(&Config::parse(config_json)?, ok, stdout, now_epoch)?;
    // Supervisor state needs insertion order; health snapshots retain the
    // sorted object order emitted before enabling serde_json preserve_order.
    snapshot.sort_all_objects();
    Ok(snapshot)
}
