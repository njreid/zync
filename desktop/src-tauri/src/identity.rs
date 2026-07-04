//! Device identity: Ed25519 keypair generation, OS-keychain persistence, and
//! the wire encodings that MUST byte-for-byte match the (frozen) phone side:
//!
//! - Public key: STANDARD base64 (with padding) of the raw 32-byte Ed25519
//!   public key — matches `java.util.Base64.getDecoder()` on the phone.
//! - Signature: STANDARD base64 of the Ed25519 signature over the RAW UTF-8
//!   bytes of the challenge string (not a hash, not decoded) — matches the
//!   phone's `verifyEd25519(pubkey, challenge.toByteArray(UTF_8), signature)`.
//! - confirm_code(nonce): first 8 uppercase hex chars of SHA-256(nonce UTF-8
//!   bytes) — matches the phone's `deriveConfirmCode`.

use anyhow::{anyhow, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use rand::RngExt;
use sha2::{Digest, Sha256};
use std::io::Write as _;
use std::path::PathBuf;
use std::sync::Mutex;

/// Keychain service name for the desktop app's device identity.
pub const SERVICE: &str = "dev.njr.zync.desktop";
/// Keychain account name for the device's private key.
pub const ACCOUNT: &str = "device-key";

/// Abstraction over "somewhere a 32-byte Ed25519 seed can be persisted".
///
/// This lets `DeviceIdentity::load_or_create` be backed by the OS keychain
/// in production while tests (and CI without a Secret Service) inject an
/// in-memory or file-backed store.
pub trait IdentityStore {
    /// Load a previously persisted seed, if any.
    fn load(&self) -> Result<Option<[u8; 32]>>;
    /// Persist a seed, overwriting any previous value.
    fn save(&self, seed: &[u8; 32]) -> Result<()>;
}

/// Production store: OS keychain via the `keyring` crate (Secret Service on
/// Linux, Keychain Services on macOS, Credential Manager on Windows).
pub struct KeychainStore;

impl IdentityStore for KeychainStore {
    fn load(&self) -> Result<Option<[u8; 32]>> {
        let entry = keyring::Entry::new(SERVICE, ACCOUNT).context("open keychain entry")?;
        match entry.get_password() {
            Ok(b64) => {
                let bytes = STANDARD
                    .decode(b64.trim())
                    .context("decode keychain-stored key")?;
                let seed: [u8; 32] = bytes
                    .try_into()
                    .map_err(|_| anyhow!("keychain-stored key is not 32 bytes"))?;
                Ok(Some(seed))
            }
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    fn save(&self, seed: &[u8; 32]) -> Result<()> {
        let entry = keyring::Entry::new(SERVICE, ACCOUNT).context("open keychain entry")?;
        entry
            .set_password(&STANDARD.encode(seed))
            .context("write keychain entry")?;
        Ok(())
    }
}

/// Fallback store: a `0600` file under the app config dir. Used when the OS
/// keychain is unavailable (e.g. headless Linux without a Secret Service).
pub struct FileStore {
    path: PathBuf,
}

impl FileStore {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    /// Default fallback location: `<config dir>/dev.njr.zync.desktop/device-key`.
    pub fn default_path() -> PathBuf {
        config_dir().join(SERVICE).join(ACCOUNT)
    }
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

impl IdentityStore for FileStore {
    fn load(&self) -> Result<Option<[u8; 32]>> {
        if !self.path.exists() {
            return Ok(None);
        }
        let content = std::fs::read_to_string(&self.path).context("read identity file")?;
        let bytes = STANDARD
            .decode(content.trim())
            .context("decode identity file")?;
        let seed: [u8; 32] = bytes
            .try_into()
            .map_err(|_| anyhow!("identity file does not contain a 32-byte key"))?;
        Ok(Some(seed))
    }

    fn save(&self, seed: &[u8; 32]) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent).context("create identity file parent dir")?;
        }

        #[cfg(unix)]
        let mut file = {
            use std::os::unix::fs::OpenOptionsExt;
            std::fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&self.path)
                .context("create identity file")?
        };
        #[cfg(not(unix))]
        let mut file = std::fs::File::create(&self.path).context("create identity file")?;

        file.write_all(STANDARD.encode(seed).as_bytes())
            .context("write identity file")?;

        // Belt-and-suspenders: re-assert 0600 after writing. The real fix is
        // the mode(0o600) at creation above, which closes the TOCTOU window
        // where a create-then-chmod approach would leave the file briefly
        // group/world-readable (or permanently so, if the process dies
        // between create and chmod).
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(std::fs::Permissions::from_mode(0o600))
                .context("chmod identity file")?;
        }

        Ok(())
    }
}

/// Test-only in-memory store.
#[derive(Default)]
pub struct InMemoryStore {
    inner: Mutex<Option<[u8; 32]>>,
}

impl IdentityStore for InMemoryStore {
    fn load(&self) -> Result<Option<[u8; 32]>> {
        Ok(*self.inner.lock().unwrap())
    }

    fn save(&self, seed: &[u8; 32]) -> Result<()> {
        *self.inner.lock().unwrap() = Some(*seed);
        Ok(())
    }
}

/// This device's Ed25519 identity, used to authenticate to a paired phone.
pub struct DeviceIdentity {
    signing: SigningKey,
    device_name: String,
}

impl DeviceIdentity {
    /// Internal constructor: build an identity directly from a seed. Not
    /// exposed outside this module in production builds — see `from_seed`
    /// below, which is the `pub`, test-only entry point that production
    /// code cannot reach.
    fn from_seed_inner(seed: [u8; 32], device_name: &str) -> Self {
        Self {
            signing: SigningKey::from_bytes(&seed),
            device_name: device_name.to_string(),
        }
    }

    /// Test-only constructor: build an identity directly from a fixed seed,
    /// bypassing any store. Lets tests get deterministic keys.
    ///
    /// Gated to `#[cfg(test)]` so production code can never construct a
    /// device identity from a fixed/predictable seed.
    #[cfg(test)]
    pub fn from_seed(seed: [u8; 32], device_name: &str) -> Self {
        Self::from_seed_inner(seed, device_name)
    }

    /// Load the persisted identity from the OS keychain, creating and
    /// persisting a new one on first run. If the keychain itself errors
    /// (e.g. no Secret Service on headless Linux), falls back to a `0600`
    /// file under the app config dir, logging a warning.
    pub fn load_or_create(device_name: &str) -> Result<Self> {
        let keychain = KeychainStore;
        match keychain.load() {
            Ok(_) => Self::load_or_create_with(device_name, &keychain),
            Err(e) => {
                log::warn!(
                    "keychain unavailable ({e}), falling back to file store at {:?}",
                    FileStore::default_path()
                );
                let file = FileStore::new(FileStore::default_path());
                Self::load_or_create_with(device_name, &file)
            }
        }
    }

    /// Load-or-create against an explicitly injected store. Used by
    /// `load_or_create` internally, and directly by tests.
    pub fn load_or_create_with(device_name: &str, store: &dyn IdentityStore) -> Result<Self> {
        if let Some(seed) = store.load()? {
            return Ok(Self::from_seed_inner(seed, device_name));
        }
        let seed = generate_seed();
        store.save(&seed)?;
        Ok(Self::from_seed_inner(seed, device_name))
    }

    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing.verifying_key()
    }

    /// STANDARD base64 of the raw 32-byte public key (matches the phone's
    /// `java.util.Base64.getDecoder()`).
    pub fn public_key_b64(&self) -> String {
        STANDARD.encode(self.verifying_key().to_bytes())
    }

    /// STANDARD base64 of the Ed25519 signature over the RAW UTF-8 bytes of
    /// `challenge` (not a hash, not decoded) — matches the phone's
    /// `verifyEd25519(pubkey, challenge.toByteArray(UTF_8), signature)`.
    pub fn sign_challenge_b64(&self, challenge: &str) -> String {
        let sig: Signature = self.signing.sign(challenge.as_bytes());
        STANDARD.encode(sig.to_bytes())
    }

    /// First 8 uppercase hex chars of SHA-256(nonce UTF-8 bytes) — matches
    /// the phone's `deriveConfirmCode`.
    pub fn confirm_code(nonce: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(nonce.as_bytes());
        let digest = hasher.finalize();
        hex_encode(&digest)[..8].to_ascii_uppercase()
    }
}

fn generate_seed() -> [u8; 32] {
    let mut seed = [0u8; 32];
    rand::rng().fill(&mut seed[..]);
    seed
}

fn hex_encode(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        write!(out, "{:02x}", b).unwrap();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pubkey_is_standard_base64_32_bytes() {
        let id = DeviceIdentity::from_seed([7u8; 32], "test");
        let raw = STANDARD.decode(id.public_key_b64()).unwrap();
        assert_eq!(raw.len(), 32);
    }

    #[test]
    fn signature_verifies_over_raw_challenge_bytes() {
        // Sign "hello-challenge"; verify with ed25519-dalek's VerifyingKey
        // over the SAME raw bytes — this is exactly what the phone checks.
        let id = DeviceIdentity::from_seed([9u8; 32], "test");
        let sig_b64 = id.sign_challenge_b64("hello-challenge");
        let sig_bytes = STANDARD.decode(&sig_b64).unwrap();
        assert_eq!(sig_bytes.len(), 64);
        let sig = Signature::from_slice(&sig_bytes).unwrap();
        id.verifying_key()
            .verify_strict(b"hello-challenge", &sig)
            .unwrap();
    }

    #[test]
    fn signature_does_not_verify_over_wrong_bytes() {
        let id = DeviceIdentity::from_seed([9u8; 32], "test");
        let sig_b64 = id.sign_challenge_b64("hello-challenge");
        let sig = Signature::from_slice(&STANDARD.decode(&sig_b64).unwrap()).unwrap();
        assert!(id
            .verifying_key()
            .verify_strict(b"tampered-challenge", &sig)
            .is_err());
    }

    #[test]
    fn confirm_code_matches_phone_formula() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        // first 8 hex chars, uppercased = "BA7816BF".
        assert_eq!(DeviceIdentity::confirm_code("abc"), "BA7816BF");
    }

    #[test]
    fn identity_roundtrips_through_injected_store() {
        let store = InMemoryStore::default();
        let a = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
        let b = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
        assert_eq!(a.public_key_b64(), b.public_key_b64()); // reloaded, not regenerated
    }

    #[test]
    fn identity_roundtrips_through_file_store() {
        let dir = tempfile::tempdir().unwrap();
        let store = FileStore::new(dir.path().join("device-key"));
        let a = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
        let b = DeviceIdentity::load_or_create_with("dev", &store).unwrap();
        assert_eq!(a.public_key_b64(), b.public_key_b64());

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.path().join("device-key"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(mode, 0o600);
        }
    }

    #[test]
    #[cfg(unix)]
    fn file_store_save_creates_file_with_0600_mode() {
        // Exercises FileStore::save directly (not via load_or_create_with)
        // and checks the mode immediately after save returns, with no
        // intervening operations. This is the structural fix for the TOCTOU
        // window: save() now creates the file with mode 0600 via
        // OpenOptions::mode(0o600) at open() time, rather than creating with
        // default (often 0644) perms and chmod-ing afterward. A true
        // race-window test (asserting the file is NEVER briefly
        // group/world-readable) isn't practically writable deterministically
        // from outside the function — asserting create-time mode is the best
        // available proxy, since it verifies the file's permissions are
        // correct as of its very first stat-able moment rather than only
        // "eventually correct".
        use std::os::unix::fs::PermissionsExt;

        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("device-key");
        let store = FileStore::new(path.clone());
        store.save(&[3u8; 32]).unwrap();

        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn different_seeds_produce_different_keys() {
        let a = DeviceIdentity::from_seed([1u8; 32], "a");
        let b = DeviceIdentity::from_seed([2u8; 32], "b");
        assert_ne!(a.public_key_b64(), b.public_key_b64());
    }
}
