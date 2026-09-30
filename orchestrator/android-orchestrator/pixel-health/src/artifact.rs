//! Artifact source, integrity and release admission. Platform code owns copy/root fallback effects.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{Read, Result as IoResult},
};
fn local_source(url: &str) -> bool {
    let url = crate::trim(url);
    let lower = url.to_ascii_lowercase();
    !url.is_empty()
        && !lower.starts_with("http://")
        && !lower.starts_with("https://")
        && if lower.starts_with("file://") {
            url[7..].starts_with('/')
        } else {
            url.starts_with('/')
        }
}
fn field<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().unwrap_or("")
}
fn entries(value: &Value) -> &[Value] {
    value.as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn required(entry: &Value) -> bool {
    entry["required"].as_bool().unwrap_or(true)
}
fn component_error(args: &Value) -> Option<String> {
    let manifest = &args["manifest"];
    let component = field(args, "component");
    let facade = args["facade"].as_bool().unwrap_or(false);
    let signature_error = || {
        format!(
            "Unsupported component release signature schema: {}",
            field(manifest, "signatureSchema")
        )
    };
    let valid_signature = field(manifest, "signatureSchema").to_lowercase() == "none";
    if facade && !valid_signature {
        return Some(signature_error());
    }
    if manifest["schema"].as_i64().unwrap_or(1) != 1 {
        return Some(format!(
            "Unsupported component release schema: {}",
            manifest["schema"]
        ));
    }
    if field(manifest, "componentId") != component {
        return Some(format!(
            "Component release manifest targets {}, expected {component}",
            field(manifest, "componentId")
        ));
    }
    if !facade && !valid_signature {
        return Some(signature_error());
    }
    if crate::trim(field(manifest, "releaseId")).is_empty() {
        return Some(format!("Component release id is required for {component}"));
    }
    let artifacts = entries(&manifest["artifacts"]);
    if artifacts.is_empty() {
        return Some(format!(
            "Component release artifacts are required for {component}"
        ));
    }
    if component == "dns"
        && (artifacts.len() != 2
            || !["adguardhome-rootfs", "dns-runtime-assets"]
                .iter()
                .all(|id| artifacts.iter().any(|entry| field(entry, "id") == *id)))
    {
        return Some(
            "DNS component release must contain exactly adguardhome-rootfs, dns-runtime-assets"
                .into(),
        );
    }
    if facade {
        for entry in artifacts {
            if !local_source(field(entry, "url")) {
                return Some(format!(
                    "Component release artifact {} has unsupported url '{}'. Use /absolute/path or file:///absolute/path and stage with deploy_orchestrator_apk.sh --component-release-dir.",
                    field(entry, "id"),
                    field(entry, "url")
                ));
            }
        }
    }
    None
}
pub(crate) fn sha256(path: &str) -> IoResult<String> {
    let mut file = File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hash.finalize()))
}
pub(crate) fn decide(operation: &str, payload: &str) -> Result<Value, String> {
    let args: Value = serde_json::from_str(payload).map_err(|_| "invalid artifact input")?;
    let text = |key: &str| args[key].as_str().unwrap_or("");
    Ok(match operation {
        "expected_sha" => json!(crate::trim(text("sha")).to_lowercase()),
        "cache_usable" => json!(text("expected").is_empty() || args["actual"] == args["expected"]),
        "release_allowed" => json!(!args["parent"].is_null() && args["parent"] == args["cache"]),
        "bootstrap_manifest" => {
            let manifest = &args["manifest"];
            let mut failure = None;
            if field(manifest, "signatureSchema").to_lowercase() != field(&args, "signature") {
                failure = Some(format!(
                    "Unsupported manifest signature schema: {}",
                    field(manifest, "signatureSchema")
                ));
            } else if crate::trim(field(manifest, "manifestVersion")).is_empty() {
                failure = Some("Manifest version is required".into());
            } else {
                let artifacts = entries(&manifest["artifacts"]);
                'ids: for (kind, ids) in [
                    ("required", entries(&args["required"])),
                    ("optional", entries(&args["optional"])),
                ] {
                    for id in ids.iter().filter_map(Value::as_str) {
                        let entry = artifacts.iter().find(|entry| field(entry, "id") == id);
                        if let Some(entry) = entry {
                            if !required(entry) {
                                failure = Some(if kind == "required" {
                                    format!("Required artifact must set required=true: {id}")
                                } else {
                                    format!(
                                        "Bootstrap artifact must set required=true when present: {id}"
                                    )
                                });
                                break 'ids;
                            }
                            if !local_source(field(entry, "url")) {
                                failure = Some(format!(
                                    "Artifact {} has unsupported url '{}'. Use /absolute/path or file:///absolute/path and stage with deploy_orchestrator_apk.sh --runtime-bundle-dir.",
                                    field(entry, "id"),
                                    field(entry, "url")
                                ));
                                break 'ids;
                            }
                        } else if kind == "required" {
                            failure = Some(format!("Missing required artifact: {id}"));
                            break 'ids;
                        }
                    }
                }
            }
            json!(failure)
        }
        "required_manifest" => {
            let artifacts = entries(&args["manifest"]["artifacts"]);
            let mut ids = Vec::new();
            if let Some(rootfs) = args["rootfs"].as_str() {
                ids.push(rootfs);
            }
            ids.extend(["dropbear-bundle", "tailscale-bundle"]);
            let mut failure = None;
            for id in ids {
                if let Some(entry) = artifacts.iter().find(|entry| field(entry, "id") == id) {
                    if !required(entry) {
                        failure = Some(format!("Required artifact must set required=true: {id}"));
                        break;
                    }
                } else {
                    failure = Some(format!("Missing required artifact in manifest: {id}"));
                    break;
                }
            }
            json!(failure)
        }
        "component_manifest" => json!(component_error(&args)),
        "component_order" => {
            let artifacts = entries(&args["artifacts"]);
            let mut failure = None;
            let indices = if text("component") != "dns" {
                (0..artifacts.len()).collect::<Vec<_>>()
            } else {
                let mut indices = Vec::new();
                for id in ["adguardhome-rootfs", "dns-runtime-assets"] {
                    if let Some(index) =
                        artifacts.iter().rposition(|entry| field(entry, "id") == id)
                    {
                        indices.push(index);
                    } else {
                        failure = Some(format!("Missing required dns release artifact: {id}"));
                        break;
                    }
                }
                indices
            };
            json!({"error":failure,"indices":indices})
        }
        "source" => {
            let url = crate::trim(text("url"));
            let id = text("id");
            let lower = url.to_ascii_lowercase();
            if url.is_empty() {
                json!({"error":format!("Artifact source url is blank for {id}"),"path":null})
            } else if lower.starts_with("http://") || lower.starts_with("https://") {
                json!({"error":format!("Remote artifact source is not allowed for {id}: {url}"),"path":null})
            } else {
                let path = if lower.starts_with("file://") {
                    &url[7..]
                } else {
                    url
                };
                if !path.starts_with('/') {
                    json!({"error":format!("Artifact source must be an absolute local path for {id}: {url}"),"path":null})
                } else {
                    json!({"error":null,"path":path})
                }
            }
        }
        _ => return Err("unknown artifact policy operation".into()),
    })
}
