//! Local reverse proxy: the webview loads `http://127.0.0.1:<port>/` and
//! every request is forwarded to the paired phone's `https://<host>:<tls_port>`
//! over a pinned-TLS `Enforce` client, with the desktop's session token
//! injected as `Authorization: Bearer <token>` (overwriting anything the
//! caller supplied — the webview never sees or needs to know the token).
//!
//! `GET /api/events` is treated specially: it's a WebSocket endpoint, so
//! instead of a request/response forward it upgrades the inbound connection
//! and bridges frames bidirectionally to an outbound `wss://` connection to
//! the phone, opened over the same pinned TLS trust as the HTTP leg.

use crate::pairing::PairedPhone;
use crate::pinning::{pinned_client, pinned_rustls_config, PinMode};
use anyhow::{Context, Result};
use axum::body::Body;
use axum::extract::ws::{Message as AxumMessage, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use futures_util::{SinkExt, StreamExt};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message as TungsteniteMessage;

/// A running proxy: bound to an ephemeral `127.0.0.1` port, forwarding to
/// one specific paired phone. Send on (or simply drop) `shutdown` to
/// gracefully stop the server — both trigger the same `oneshot::Receiver`
/// resolving (a send resolves it with `Ok(())`; a drop resolves it with
/// `Err(RecvError)`, which is treated identically via `.ok()`).
pub struct ProxyHandle {
    pub port: u16,
    pub shutdown: tokio::sync::oneshot::Sender<()>,
}

struct ProxyState {
    paired: PairedPhone,
    client: reqwest::Client,
}

/// Headers that must never be blindly forwarded across a proxy hop (RFC
/// 7230 §6.1 hop-by-hop headers, plus `host` since the outbound request
/// targets a different authority).
const HOP_BY_HOP: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "host",
];

fn strip_hop_by_hop(headers: &HeaderMap) -> HeaderMap {
    let mut out = HeaderMap::new();
    for (name, value) in headers.iter() {
        if HOP_BY_HOP.iter().any(|h| name.as_str().eq_ignore_ascii_case(h)) {
            continue;
        }
        out.append(name.clone(), value.clone());
    }
    out
}

fn base_url(paired: &PairedPhone) -> String {
    match paired.host {
        std::net::IpAddr::V6(v6) => format!("https://[{v6}]:{}", paired.tls_port),
        std::net::IpAddr::V4(_) => format!("https://{}:{}", paired.host, paired.tls_port),
    }
}

/// Start the reverse proxy for `paired`, binding on an ephemeral
/// `127.0.0.1` port. The returned `ProxyHandle::port` is the port the
/// webview should be pointed at (see `commands::proxy_url_logic`).
pub async fn start_proxy(paired: PairedPhone) -> Result<ProxyHandle> {
    let client = pinned_client(PinMode::Enforce(paired.fingerprint.clone()));
    let state = Arc::new(ProxyState { paired, client });

    let app = Router::new()
        .route("/api/events", get(ws_handler))
        .fallback(forward_handler)
        .with_state(state);

    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
        .await
        .context("bind proxy listener")?;
    let port = listener.local_addr().context("proxy local_addr")?.port();

    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();

    tokio::spawn(async move {
        let result = axum::serve(listener, app.into_make_service())
            .with_graceful_shutdown(async {
                // A send() resolves this Ok(()); dropping the sender
                // resolves it Err(RecvError) — both are treated as "shut
                // down now" via .ok(), so either action stops the server.
                let _ = shutdown_rx.await;
            })
            .await;
        if let Err(e) = result {
            log::error!("proxy server exited with error: {e}");
        }
    });

    Ok(ProxyHandle { port, shutdown: shutdown_tx })
}

async fn forward_handler(
    State(state): State<Arc<ProxyState>>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Body,
) -> Response {
    match forward(&state, method, uri, headers, body).await {
        Ok(resp) => resp,
        Err(e) => {
            log::error!("proxy forward error: {e:#}");
            (StatusCode::BAD_GATEWAY, format!("proxy error: {e:#}")).into_response()
        }
    }
}

async fn forward(
    state: &ProxyState,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Body,
) -> Result<Response> {
    let path_and_query = uri
        .path_and_query()
        .map(|pq| pq.as_str())
        .unwrap_or("/");
    let url = format!("{}{}", base_url(&state.paired), path_and_query);

    let body_bytes = axum::body::to_bytes(body, usize::MAX)
        .await
        .context("buffer inbound request body")?;

    let mut out_headers = strip_hop_by_hop(&headers);
    // Overwrite (not merge) any caller-supplied Authorization: the webview
    // never has, or needs, the real session token.
    out_headers.remove(axum::http::header::AUTHORIZATION);
    out_headers.insert(
        axum::http::header::AUTHORIZATION,
        HeaderValue::from_str(&format!("Bearer {}", state.paired.session_token))
            .context("build bearer auth header")?,
    );

    let reqwest_method =
        reqwest::Method::from_bytes(method.as_str().as_bytes()).context("translate method")?;

    let response = state
        .client
        .request(reqwest_method, &url)
        .headers(convert_headers_to_reqwest(&out_headers)?)
        .body(body_bytes.to_vec())
        .send()
        .await
        .context("forward request to paired phone")?;

    let status = response.status().as_u16();
    let resp_headers = convert_headers_from_reqwest(response.headers());
    let resp_headers = strip_hop_by_hop(&resp_headers);

    let stream = response.bytes_stream();
    let axum_body = Body::from_stream(stream);

    let mut builder = Response::builder().status(status);
    if let Some(map) = builder.headers_mut() {
        *map = resp_headers;
    }
    Ok(builder.body(axum_body).context("build proxied response")?)
}

fn convert_headers_to_reqwest(headers: &HeaderMap) -> Result<reqwest::header::HeaderMap> {
    let mut out = reqwest::header::HeaderMap::new();
    for (name, value) in headers.iter() {
        let name = reqwest::header::HeaderName::from_bytes(name.as_str().as_bytes())
            .context("translate header name")?;
        let value = reqwest::header::HeaderValue::from_bytes(value.as_bytes())
            .context("translate header value")?;
        out.append(name, value);
    }
    Ok(out)
}

fn convert_headers_from_reqwest(headers: &reqwest::header::HeaderMap) -> HeaderMap {
    let mut out = HeaderMap::new();
    for (name, value) in headers.iter() {
        if let (Ok(name), Ok(value)) = (
            HeaderName::from_bytes(name.as_str().as_bytes()),
            HeaderValue::from_bytes(value.as_bytes()),
        ) {
            out.append(name, value);
        }
    }
    out
}

async fn ws_handler(State(state): State<Arc<ProxyState>>, ws: WebSocketUpgrade) -> Response {
    ws.on_upgrade(move |socket| bridge_events(socket, state))
}

async fn bridge_events(inbound: WebSocket, state: Arc<ProxyState>) {
    if let Err(e) = bridge_events_inner(inbound, &state).await {
        log::error!("proxy WS bridge error: {e:#}");
    }
}

async fn bridge_events_inner(mut inbound: WebSocket, state: &ProxyState) -> Result<()> {
    let paired = &state.paired;
    let ws_url = match paired.host {
        std::net::IpAddr::V6(v6) => format!("wss://[{v6}]:{}/api/events", paired.tls_port),
        std::net::IpAddr::V4(_) => format!("wss://{}:{}/api/events", paired.host, paired.tls_port),
    };

    let mut request = ws_url
        .as_str()
        .into_client_request()
        .context("build outbound WS request")?;
    request.headers_mut().insert(
        axum::http::header::AUTHORIZATION,
        HeaderValue::from_str(&format!("Bearer {}", paired.session_token))
            .context("build outbound WS auth header")?,
    );

    let tls_config = pinned_rustls_config(PinMode::Enforce(paired.fingerprint.clone()));
    let connector = tokio_tungstenite::Connector::Rustls(Arc::new(tls_config));

    let (outbound, _resp) = tokio_tungstenite::connect_async_tls_with_config(
        request,
        None,
        false,
        Some(connector),
    )
    .await
    .context("connect outbound pinned WS to paired phone")?;

    let (mut out_write, mut out_read) = outbound.split();

    loop {
        tokio::select! {
            inbound_msg = inbound.recv() => {
                match inbound_msg {
                    Some(Ok(msg)) => {
                        let Some(converted) = axum_to_tungstenite(msg) else { continue };
                        let is_close = matches!(converted, TungsteniteMessage::Close(_));
                        if out_write.send(converted).await.is_err() || is_close {
                            break;
                        }
                    }
                    _ => break,
                }
            }
            outbound_msg = out_read.next() => {
                match outbound_msg {
                    Some(Ok(msg)) => {
                        let is_close = matches!(msg, TungsteniteMessage::Close(_));
                        let Some(converted) = tungstenite_to_axum(msg) else { continue };
                        if inbound.send(converted).await.is_err() || is_close {
                            break;
                        }
                    }
                    _ => break,
                }
            }
        }
    }

    let _ = out_write.close().await;
    let _ = inbound.close().await;
    Ok(())
}

fn axum_to_tungstenite(msg: AxumMessage) -> Option<TungsteniteMessage> {
    Some(match msg {
        AxumMessage::Text(t) => TungsteniteMessage::Text(t.to_string().into()),
        AxumMessage::Binary(b) => TungsteniteMessage::Binary(b),
        AxumMessage::Ping(p) => TungsteniteMessage::Ping(p),
        AxumMessage::Pong(p) => TungsteniteMessage::Pong(p),
        AxumMessage::Close(c) => TungsteniteMessage::Close(c.map(|f| {
            tokio_tungstenite::tungstenite::protocol::CloseFrame {
                code: f.code.into(),
                reason: f.reason.to_string().into(),
            }
        })),
    })
}

fn tungstenite_to_axum(msg: TungsteniteMessage) -> Option<AxumMessage> {
    Some(match msg {
        TungsteniteMessage::Text(t) => AxumMessage::Text(t.to_string().into()),
        TungsteniteMessage::Binary(b) => AxumMessage::Binary(b),
        TungsteniteMessage::Ping(p) => AxumMessage::Ping(p),
        TungsteniteMessage::Pong(p) => AxumMessage::Pong(p),
        TungsteniteMessage::Close(c) => AxumMessage::Close(c.map(|f| axum::extract::ws::CloseFrame {
            code: f.code.into(),
            reason: f.reason.to_string().into(),
        })),
        TungsteniteMessage::Frame(_) => return None,
    })
}
