//! Pinned-TLS: a custom rustls `ServerCertVerifier` that implements
//! trust-on-first-use (capture) then strict pinning (enforce), matching the
//! phone's fingerprint format byte-for-byte.
//!
//! Deliberately skips WebPKI/root-of-trust validation: on a LAN pairing flow
//! there is no CA, and pinning the exact leaf certificate is strictly
//! stronger than CA validation for this threat model (an on-path attacker
//! with a "valid" cert for some other identity is exactly what pinning is
//! meant to defeat). The two supported modes:
//!
//! - `CaptureOnce`: records the leaf certificate's fingerprint the first
//!   (and only) time a connection is made, and accepts the connection. Used
//!   only for the initial `/pair/request` exchange, whose result is then
//!   cross-checked against two independent signals (the server's own claim
//!   about its cert, and a locally-computable confirm code) before it is
//!   ever trusted for anything else.
//! - `Enforce(fingerprint)`: accepts a connection only if the leaf
//!   certificate's fingerprint exactly equals the pinned fingerprint.
//!   Anything else — including a perfectly valid cert for a different
//!   identity — is rejected with a TLS error.

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as TlsError, SignatureScheme};
use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};

/// SHA-256 of the certificate DER, formatted uppercase colon-separated hex
/// (e.g. `AB:CD:EF:...`) — byte-for-byte the phone's `certFingerprint`
/// format.
pub fn leaf_fingerprint(cert_der: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(cert_der);
    let digest = hasher.finalize();
    digest
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<_>>()
        .join(":")
}

/// How a pinned client should treat the server's leaf certificate.
#[derive(Clone)]
pub enum PinMode {
    /// Trust-on-first-use: record the leaf fingerprint into the shared slot
    /// and accept. Intended for exactly one exchange, whose result is
    /// cross-checked out-of-band before being trusted further.
    CaptureOnce(Arc<Mutex<Option<String>>>),
    /// Accept only a connection whose leaf fingerprint matches exactly.
    Enforce(String),
}

impl PinMode {
    /// Convenience constructor for `CaptureOnce` with a fresh empty slot.
    pub fn capture_once() -> (Self, Arc<Mutex<Option<String>>>) {
        let slot = Arc::new(Mutex::new(None));
        (Self::CaptureOnce(slot.clone()), slot)
    }
}

#[derive(Debug)]
struct PinnedVerifier {
    mode: PinMode,
    provider: Arc<CryptoProvider>,
}

impl std::fmt::Debug for PinMode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PinMode::CaptureOnce(_) => write!(f, "CaptureOnce"),
            PinMode::Enforce(fp) => write!(f, "Enforce({fp})"),
        }
    }
}

impl ServerCertVerifier for PinnedVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        let fingerprint = leaf_fingerprint(end_entity.as_ref());
        match &self.mode {
            PinMode::CaptureOnce(slot) => {
                *slot.lock().unwrap() = Some(fingerprint);
                Ok(ServerCertVerified::assertion())
            }
            PinMode::Enforce(pinned) => {
                if &fingerprint == pinned {
                    Ok(ServerCertVerified::assertion())
                } else {
                    Err(TlsError::General(format!(
                        "pinned certificate mismatch: expected {pinned}, got {fingerprint}"
                    )))
                }
            }
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// Build a `rustls::ClientConfig` that pins server certificates per `mode`
/// instead of validating against a root-of-trust.
pub fn pinned_rustls_config(mode: PinMode) -> rustls::ClientConfig {
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let verifier = Arc::new(PinnedVerifier {
        mode,
        provider: provider.clone(),
    });
    rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .expect("default protocol versions are valid")
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth()
}

/// Build a `reqwest::Client` whose TLS trust decisions are entirely governed
/// by `mode` (see `PinMode`), wired via `use_preconfigured_tls`.
pub fn pinned_client(mode: PinMode) -> reqwest::Client {
    let config = pinned_rustls_config(mode);
    reqwest::Client::builder()
        .use_preconfigured_tls(config)
        .build()
        .expect("pinned reqwest client builds")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leaf_fingerprint_is_uppercase_colon_hex_sha256() {
        let der = b"hello world";
        let fp = leaf_fingerprint(der);
        // SHA-256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        assert_eq!(
            fp,
            "B9:4D:27:B9:93:4D:3E:08:A5:2E:52:D7:DA:7D:AB:FA:C4:84:EF:E3:7A:53:80:EE:90:88:F7:AC:E2:EF:CD:E9"
        );
    }
}
