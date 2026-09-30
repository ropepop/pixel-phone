use serde_json::Value;
use std::{fs, io, io::Write, path::Path};

pub fn read(path: &str) -> Option<String> {
    // Missing, unreadable and malformed UTF-8 files retain the existing default
    // behavior. Never repair or rewrite a file during a read.
    fs::read_to_string(path).ok()
}

pub fn write(path: &str, body: &str) -> io::Result<()> {
    let path = Path::new(path);
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "store path needs a parent"))?;
    let name = path.file_name().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "store path needs a file name")
    })?;
    fs::create_dir_all(parent)?;
    let mut prefix = name.to_os_string();
    prefix.push(".");
    let mut staging = tempfile::Builder::new()
        .prefix(&prefix)
        .suffix(".tmp")
        .tempfile_in(parent)?;
    staging.write_all(body.as_bytes())?;
    // Same-directory rename is atomic on the supported macOS/Android filesystems.
    // As before, this does not promise durability across power loss (no fsync).
    // Normal errors clean only this operation's staging file. Process death can
    // leave an orphan, which readers ignore; no broad cleanup can race a writer.
    staging.persist(path).map_err(|error| error.error)?;
    Ok(())
}

pub fn legacy_aliases(remote_strings: &str) -> Result<String, String> {
    let remote: Value =
        serde_json::from_str(remote_strings).map_err(|_| "invalid configuration bridge input")?;
    let set = |key: &str| remote[key].as_str().is_some_and(secret_is_set);
    let aliases: Vec<_> = [
        ("dohPathToken", "dohSecretToken"),
        ("adminUsername", "adminBasicAuthUser"),
        ("adminPasswordFile", "adminBasicAuthPasswordFile"),
    ]
    .into_iter()
    .filter(|(new, old)| !set(new) && set(old))
    .collect();
    // Return key selections, not values: Kotlin retains arbitrary JSON numbers,
    // unknown fields, exact UTF-16 strings, original order and caller formatting.
    Ok(serde_json::to_string(&aliases).expect("string pairs serialize"))
}

pub fn secret_is_set(value: &str) -> bool {
    !value.chars().all(crate::whitespace)
}
