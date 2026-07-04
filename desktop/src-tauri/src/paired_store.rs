//! Persistence for successfully-paired phones: `PairedPhone` plus a name,
//! stored either in the OS keychain (production) or a fallback `0600` file
//! tree, mirroring the pattern in `identity.rs`.
//!
//! `pairing::PairedPhone` is intentionally not `Serialize`/`Deserialize`
//! (this module doesn't own it and must not modify `pairing.rs`), so a small
//! local DTO (`PairedPhoneRecord`) carries the wire/on-disk representation.

use crate::pairing::PairedPhone;
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write as _;
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::Mutex;

/// Keychain service name for paired-phone records.
pub const SERVICE: &str = "dev.njr.zync.desktop.paired";
/// Fixed account name for the side-index of known phone names (OS keychains
/// don't support enumeration by service, so the index is itself a keychain
/// entry storing a JSON array of names).
pub const INDEX_ACCOUNT: &str = "__index__";

/// On-disk / keychain-storable representation of a `PairedPhone`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedPhoneRecord {
    pub host: String,
    pub tls_port: u16,
    pub fingerprint: String,
    pub session_token: String,
}

impl From<&PairedPhone> for PairedPhoneRecord {
    fn from(p: &PairedPhone) -> Self {
        Self {
            host: p.host.to_string(),
            tls_port: p.tls_port,
            fingerprint: p.fingerprint.clone(),
            session_token: p.session_token.clone(),
        }
    }
}

impl PairedPhoneRecord {
    pub fn to_paired_phone(&self) -> Result<PairedPhone> {
        let host: IpAddr = self
            .host
            .parse()
            .with_context(|| format!("paired phone record has an invalid host: {}", self.host))?;
        Ok(PairedPhone {
            host,
            tls_port: self.tls_port,
            fingerprint: self.fingerprint.clone(),
            session_token: self.session_token.clone(),
        })
    }
}

/// Abstraction over "somewhere named `PairedPhone` records can be
/// persisted", parallel to `identity::IdentityStore`.
pub trait PairedStore: Send + Sync {
    fn save(&self, name: &str, phone: &PairedPhone) -> Result<()>;
    fn load(&self, name: &str) -> Result<Option<PairedPhone>>;
    fn list(&self) -> Result<Vec<String>>;
    fn forget(&self, name: &str) -> Result<()>;
}

/// Production store: one OS-keychain entry per phone name, plus a side
/// index entry (see `INDEX_ACCOUNT`) so `list()` can enumerate names.
pub struct KeychainPairedStore;

impl KeychainPairedStore {
    fn index(&self) -> Result<Vec<String>> {
        let entry =
            keyring::Entry::new(SERVICE, INDEX_ACCOUNT).context("open keychain index entry")?;
        match entry.get_password() {
            Ok(json) => Ok(serde_json::from_str(&json).context("decode keychain index")?),
            Err(keyring::Error::NoEntry) => Ok(Vec::new()),
            Err(e) => Err(e.into()),
        }
    }

    fn save_index(&self, names: &[String]) -> Result<()> {
        let entry =
            keyring::Entry::new(SERVICE, INDEX_ACCOUNT).context("open keychain index entry")?;
        let json = serde_json::to_string(names).context("encode keychain index")?;
        entry.set_password(&json).context("write keychain index")?;
        Ok(())
    }
}

impl PairedStore for KeychainPairedStore {
    fn save(&self, name: &str, phone: &PairedPhone) -> Result<()> {
        let record = PairedPhoneRecord::from(phone);
        let json = serde_json::to_string(&record).context("encode paired phone record")?;
        let entry = keyring::Entry::new(SERVICE, name).context("open keychain entry")?;
        entry.set_password(&json).context("write keychain entry")?;

        let mut names = self.index()?;
        if !names.iter().any(|n| n == name) {
            names.push(name.to_string());
            self.save_index(&names)?;
        }
        Ok(())
    }

    fn load(&self, name: &str) -> Result<Option<PairedPhone>> {
        let entry = keyring::Entry::new(SERVICE, name).context("open keychain entry")?;
        match entry.get_password() {
            Ok(json) => {
                let record: PairedPhoneRecord =
                    serde_json::from_str(&json).context("decode paired phone record")?;
                Ok(Some(record.to_paired_phone()?))
            }
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    fn list(&self) -> Result<Vec<String>> {
        self.index()
    }

    fn forget(&self, name: &str) -> Result<()> {
        let entry = keyring::Entry::new(SERVICE, name).context("open keychain entry")?;
        match entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => {}
            Err(e) => return Err(e.into()),
        }

        let names: Vec<String> = self.index()?.into_iter().filter(|n| n != name).collect();
        self.save_index(&names)?;
        Ok(())
    }
}

/// Fallback store: one JSON file per phone name under a directory, `0600`.
pub struct FilePairedStore {
    dir: PathBuf,
}

impl FilePairedStore {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    /// Default fallback location: `<config dir>/dev.njr.zync.desktop.paired/`.
    pub fn default_dir() -> PathBuf {
        config_dir().join(SERVICE)
    }

    fn path_for(&self, name: &str) -> PathBuf {
        self.dir.join(format!("{}.json", sanitize_name(name)))
    }
}

fn sanitize_name(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect()
}

fn config_dir() -> PathBuf {
    if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
        return PathBuf::from(xdg);
    }
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home).join(".config");
    }
    std::env::temp_dir()
}

impl PairedStore for FilePairedStore {
    fn save(&self, name: &str, phone: &PairedPhone) -> Result<()> {
        std::fs::create_dir_all(&self.dir).context("create paired-phone store dir")?;
        let record = PairedPhoneRecord::from(phone);
        let json = serde_json::to_string(&record).context("encode paired phone record")?;

        let path = self.path_for(name);
        #[cfg(unix)]
        let mut file = {
            use std::os::unix::fs::OpenOptionsExt;
            std::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&path)
                .context("create paired phone file")?
        };
        #[cfg(not(unix))]
        let mut file = std::fs::File::create(&path).context("create paired phone file")?;

        file.write_all(json.as_bytes()).context("write paired phone file")?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(std::fs::Permissions::from_mode(0o600))
                .context("chmod paired phone file")?;
        }
        Ok(())
    }

    fn load(&self, name: &str) -> Result<Option<PairedPhone>> {
        let path = self.path_for(name);
        if !path.exists() {
            return Ok(None);
        }
        let json = std::fs::read_to_string(&path).context("read paired phone file")?;
        let record: PairedPhoneRecord =
            serde_json::from_str(&json).context("decode paired phone record")?;
        Ok(Some(record.to_paired_phone()?))
    }

    fn list(&self) -> Result<Vec<String>> {
        if !self.dir.exists() {
            return Ok(Vec::new());
        }
        let mut names = Vec::new();
        for entry in std::fs::read_dir(&self.dir).context("read paired-phone store dir")? {
            let entry = entry.context("read paired-phone store dir entry")?;
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) == Some("json") {
                if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
                    names.push(stem.to_string());
                }
            }
        }
        Ok(names)
    }

    fn forget(&self, name: &str) -> Result<()> {
        let path = self.path_for(name);
        if path.exists() {
            std::fs::remove_file(&path).context("remove paired phone file")?;
        }
        Ok(())
    }
}

/// Test-only in-memory store.
#[derive(Default)]
pub struct InMemoryPairedStore {
    inner: Mutex<HashMap<String, PairedPhoneRecord>>,
}

impl PairedStore for InMemoryPairedStore {
    fn save(&self, name: &str, phone: &PairedPhone) -> Result<()> {
        self.inner
            .lock()
            .unwrap()
            .insert(name.to_string(), PairedPhoneRecord::from(phone));
        Ok(())
    }

    fn load(&self, name: &str) -> Result<Option<PairedPhone>> {
        match self.inner.lock().unwrap().get(name) {
            Some(record) => Ok(Some(record.to_paired_phone()?)),
            None => Ok(None),
        }
    }

    fn list(&self) -> Result<Vec<String>> {
        Ok(self.inner.lock().unwrap().keys().cloned().collect())
    }

    fn forget(&self, name: &str) -> Result<()> {
        self.inner.lock().unwrap().remove(name);
        Ok(())
    }
}

/// Pick the OS keychain, falling back to the file store if the keychain is
/// unavailable (e.g. headless Linux without a Secret Service) — mirrors
/// `DeviceIdentity::load_or_create`.
fn default_store() -> Box<dyn PairedStore> {
    let keychain = KeychainPairedStore;
    match keychain.list() {
        Ok(_) => Box::new(keychain),
        Err(e) => {
            log::warn!(
                "keychain unavailable ({e}), falling back to file store at {:?}",
                FilePairedStore::default_dir()
            );
            Box::new(FilePairedStore::new(FilePairedStore::default_dir()))
        }
    }
}

/// Save `phone` under `name` using the production store (keychain, falling
/// back to file).
pub fn save_paired_default(name: &str, phone: &PairedPhone) -> Result<()> {
    default_store().save(name, phone)
}

/// Load a previously-paired phone by name using the production store.
pub fn load_paired_default(name: &str) -> Result<Option<PairedPhone>> {
    default_store().load(name)
}

/// List known paired phone names using the production store.
pub fn list_paired_default() -> Result<Vec<String>> {
    default_store().list()
}

/// Forget a paired phone by name using the production store.
pub fn forget_default(name: &str) -> Result<()> {
    default_store().forget(name)
}

/// Testable variant of `save_paired_default` against an injected store.
pub fn save_paired(store: &dyn PairedStore, name: &str, phone: &PairedPhone) -> Result<()> {
    store.save(name, phone)
}

/// Testable variant of `load_paired_default` against an injected store.
pub fn load_paired(store: &dyn PairedStore, name: &str) -> Result<Option<PairedPhone>> {
    store.load(name)
}

/// Testable variant of `list_paired_default` against an injected store.
pub fn list_paired(store: &dyn PairedStore) -> Result<Vec<String>> {
    store.list()
}

/// Testable variant of `forget_default` against an injected store.
pub fn forget(store: &dyn PairedStore, name: &str) -> Result<()> {
    store.forget(name)
}

#[allow(dead_code)]
fn _assert_send_sync<T: Send + Sync>() {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn sample() -> PairedPhone {
        PairedPhone {
            host: IpAddr::V4(Ipv4Addr::new(192, 168, 1, 42)),
            tls_port: 8443,
            fingerprint: "AB:CD".to_string(),
            session_token: "tok123".to_string(),
        }
    }

    #[test]
    fn in_memory_store_roundtrips() {
        let store = InMemoryPairedStore::default();
        save_paired(&store, "phone-a", &sample()).unwrap();
        let loaded = load_paired(&store, "phone-a").unwrap().unwrap();
        assert_eq!(loaded.host, sample().host);
        assert_eq!(loaded.session_token, "tok123");
        assert!(list_paired(&store).unwrap().contains(&"phone-a".to_string()));
        forget(&store, "phone-a").unwrap();
        assert!(load_paired(&store, "phone-a").unwrap().is_none());
    }

    #[test]
    fn file_store_roundtrips_and_is_0600() {
        let dir = tempfile::tempdir().unwrap();
        let store = FilePairedStore::new(dir.path().to_path_buf());
        store.save("phone-b", &sample()).unwrap();
        let loaded = store.load("phone-b").unwrap().unwrap();
        assert_eq!(loaded.tls_port, 8443);
        assert!(store.list().unwrap().contains(&"phone-b".to_string()));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let path = store.path_for("phone-b");
            let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }

        store.forget("phone-b").unwrap();
        assert!(store.load("phone-b").unwrap().is_none());
    }
}
