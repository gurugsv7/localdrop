//! LocalDrop v1: bounded Noise sessions and streamed, atomic transfer batches.
use anyhow::{ensure, Context, Result};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::{Path, PathBuf},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpStream, UdpSocket},
};

pub const PORT: u16 = 45871;
pub const DISCOVERY_PORT: u16 = 45872;
pub const CHUNK: usize = 48 * 1024;
pub const MAX_ITEMS: usize = 128;
pub const MAX_TEXT: usize = 32 * 1024;
pub const MAX_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Serialize, Deserialize)]
pub struct Pairing {
    pub version: u8,
    pub id: String,
    pub secret: String,
    pub host: String,
    pub port: u16,
}
impl Pairing {
    pub fn new(host: String) -> Self {
        let mut key = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);
        Self {
            version: 1,
            id: hex::encode(&Sha256::digest(key)[..16]),
            secret: hex::encode(key),
            host,
            port: PORT,
        }
    }
    pub fn key(&self) -> Result<[u8; 32]> {
        ensure!(self.version == 1 && self.port != 0, "Unsupported pairing");
        let bytes = hex::decode(&self.secret)?;
        let key: [u8; 32] = bytes
            .try_into()
            .map_err(|_| anyhow::anyhow!("Invalid pairing key"))?;
        ensure!(
            self.id == hex::encode(&Sha256::digest(key)[..16]),
            "Invalid pairing identity"
        );
        Ok(key)
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Item {
    pub name: String,
    pub mime: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Manifest {
    pub id: String,
    pub text: Option<String>,
    pub items: Vec<Item>,
}
impl Manifest {
    pub fn validate(&self) -> Result<()> {
        ensure!(
            self.id.len() == 32 && self.id.bytes().all(|b| b.is_ascii_hexdigit()),
            "Invalid transfer ID"
        );
        ensure!(self.items.len() <= MAX_ITEMS, "Too many files");
        ensure!(
            self.text.as_ref().map_or(0, |s| s.len()) <= MAX_TEXT,
            "Text too large"
        );
        ensure!(
            self.text.is_some() || !self.items.is_empty(),
            "Empty transfer"
        );
        let mut total = 0u64;
        for i in &self.items {
            ensure!(
                !i.name.is_empty()
                    && i.name.len() <= 240
                    && !i.name.contains(['/', '\\', ':', '\0'])
                    && i.name != "."
                    && i.name != "..",
                "Unsafe filename"
            );
            ensure!(
                i.mime.len() <= 128 && i.sha256.len() == 64 && hex::decode(&i.sha256).is_ok(),
                "Invalid metadata"
            );
            total = total.checked_add(i.size).context("Size overflow")?;
        }
        ensure!(total <= MAX_BYTES, "Transfer exceeds 8 GiB");
        Ok(())
    }
}
pub fn transfer_id() -> String {
    let mut b = [0; 16];
    rand::thread_rng().fill_bytes(&mut b);
    hex::encode(b)
}

pub struct Session {
    socket: TcpStream,
    noise: snow::TransportState,
}
async fn raw_write(socket: &mut TcpStream, bytes: &[u8]) -> Result<()> {
    ensure!(bytes.len() <= 65535, "Frame too large");
    tokio::time::timeout(TIMEOUT, async {
        socket.write_u32(bytes.len() as u32).await?;
        socket.write_all(bytes).await
    })
    .await??;
    Ok(())
}
async fn raw_read(socket: &mut TcpStream) -> Result<Vec<u8>> {
    tokio::time::timeout(TIMEOUT, async {
        let len = socket.read_u32().await? as usize;
        ensure!((16..=65535).contains(&len), "Invalid frame length");
        let mut b = vec![0; len];
        socket.read_exact(&mut b).await?;
        Ok(b)
    })
    .await?
}
impl Session {
    pub async fn connect(socket: TcpStream, key: &[u8; 32], initiator: bool) -> Result<Self> {
        let mut socket = socket;
        socket.set_nodelay(true)?;
        let builder =
            snow::Builder::new("Noise_NNpsk0_25519_ChaChaPoly_SHA256".parse()?).psk(0, key)?;
        let mut handshake = if initiator {
            builder.build_initiator()?
        } else {
            builder.build_responder()?
        };
        let mut buf = [0u8; 65535];
        if initiator {
            let n = handshake.write_message(b"localdrop/1", &mut buf)?;
            raw_write(&mut socket, &buf[..n]).await?;
            let incoming = raw_read(&mut socket).await?;
            let n = handshake.read_message(&incoming, &mut buf)?;
            ensure!(&buf[..n] == b"localdrop/1", "Wrong protocol");
        } else {
            let incoming = raw_read(&mut socket).await?;
            let n = handshake.read_message(&incoming, &mut buf)?;
            ensure!(&buf[..n] == b"localdrop/1", "Wrong protocol");
            let n = handshake.write_message(b"localdrop/1", &mut buf)?;
            raw_write(&mut socket, &buf[..n]).await?;
        }
        Ok(Self {
            socket,
            noise: handshake.into_transport_mode()?,
        })
    }
    pub async fn send(&mut self, bytes: &[u8]) -> Result<()> {
        ensure!(bytes.len() <= 65519, "Payload too large");
        let mut encrypted = vec![0; bytes.len() + 16];
        let n = self.noise.write_message(bytes, &mut encrypted)?;
        raw_write(&mut self.socket, &encrypted[..n]).await
    }
    pub async fn recv(&mut self) -> Result<Vec<u8>> {
        let encrypted = raw_read(&mut self.socket).await?;
        let mut plain = vec![0; encrypted.len()];
        let n = self.noise.read_message(&encrypted, &mut plain)?;
        plain.truncate(n);
        Ok(plain)
    }
}
pub fn is_local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => v.is_private() || v.is_link_local() || v.is_loopback(),
        IpAddr::V6(v) => {
            v.is_loopback()
                || (v.segments()[0] & 0xfe00 == 0xfc00)
                || (v.segments()[0] & 0xffc0 == 0xfe80)
        }
    }
}
pub async fn discover(pair: &Pairing) -> Result<SocketAddr> {
    pair.key()?;
    let socket = UdpSocket::bind("0.0.0.0:0").await?;
    socket.set_broadcast(true)?;
    let query = format!("localdrop/1 {}", pair.id);
    let mut candidates = Vec::new();
    if let Ok(ip) = pair.host.parse::<IpAddr>() {
        if is_local(ip) {
            candidates.push(SocketAddr::new(ip, pair.port));
        }
    }
    socket
        .send_to(query.as_bytes(), (Ipv4Addr::BROADCAST, DISCOVERY_PORT))
        .await?;
    let deadline = tokio::time::Instant::now() + Duration::from_millis(750);
    let mut buf = [0; 128];
    while let Ok(Ok((n, from))) =
        tokio::time::timeout_at(deadline, socket.recv_from(&mut buf)).await
    {
        if is_local(from.ip()) && &buf[..n] == query.as_bytes() {
            candidates.insert(0, SocketAddr::new(from.ip(), pair.port));
            break;
        }
    }
    candidates
        .into_iter()
        .next()
        .context("Laptop unavailable on local Wi-Fi")
}
pub async fn advertise(id: String) -> Result<()> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, DISCOVERY_PORT)).await?;
    let expected = format!("localdrop/1 {id}");
    let mut buf = [0; 128];
    loop {
        let (n, from) = socket.recv_from(&mut buf).await?;
        if is_local(from.ip()) && &buf[..n] == expected.as_bytes() {
            socket.send_to(expected.as_bytes(), from).await?;
        }
    }
}
pub async fn describe(path: &Path, name: String, mime: String) -> Result<Item> {
    let mut file = tokio::fs::File::open(path).await?;
    let size = file.metadata().await?.len();
    ensure!(size <= MAX_BYTES, "File too large");
    let mut hash = Sha256::new();
    let mut buf = vec![0; CHUNK];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(Item {
        name,
        mime,
        size,
        sha256: hex::encode(hash.finalize()),
    })
}
pub async fn send_batch(
    session: &mut Session,
    manifest: &Manifest,
    paths: &[PathBuf],
) -> Result<()> {
    manifest.validate()?;
    ensure!(paths.len() == manifest.items.len(), "File count mismatch");
    session.send(&serde_json::to_vec(manifest)?).await?;
    let ready = session.recv().await?;
    if ready == b"committed" {
        return Ok(());
    }
    ensure!(ready == b"ready", "Receiver refused transfer");
    let mut buf = vec![0; CHUNK];
    for (item, path) in manifest.items.iter().zip(paths) {
        let mut file = tokio::fs::File::open(path).await?;
        let mut left = item.size;
        while left > 0 {
            let cap = CHUNK.min(left as usize);
            let n = file.read(&mut buf[..cap]).await?;
            ensure!(n > 0, "Source truncated");
            session.send(&buf[..n]).await?;
            left -= n as u64;
        }
        ensure!(
            file.read(&mut buf[..1]).await? == 0,
            "Source grew during transfer"
        );
    }
    ensure!(
        session.recv().await? == b"committed",
        "Transfer not committed"
    );
    Ok(())
}
/// Returns a committed batch. Caller publishes clipboard, then calls acknowledge.
pub async fn receive_batch(session: &mut Session, root: &Path) -> Result<(Manifest, Vec<PathBuf>)> {
    receive_batch_limited(session, root, MAX_BYTES).await
}
pub async fn receive_batch_limited(
    session: &mut Session,
    root: &Path,
    available: u64,
) -> Result<(Manifest, Vec<PathBuf>)> {
    let data = session.recv().await?;
    let manifest: Manifest = serde_json::from_slice(&data)?;
    manifest.validate()?;
    tokio::fs::create_dir_all(root).await?;
    let dest = root.join(&manifest.id);
    if dest.join("receipt.json").exists() {
        let prior: Manifest =
            serde_json::from_slice(&tokio::fs::read(dest.join("receipt.json")).await?)?;
        ensure!(serde_json::to_vec(&prior)? == data, "Transfer ID collision");
        let paths = batch_paths(&manifest, &dest);
        return Ok((manifest, paths));
    }
    ensure!(
        manifest
            .items
            .iter()
            .map(|i| i.size)
            .sum::<u64>()
            .saturating_add(data.len() as u64)
            <= available,
        "Cache limit reached; increase limit or clear received files"
    );
    let staging = root.join(format!("{}.partial", manifest.id));
    // Incomplete bytes may only be overwritten by this authenticated transfer.
    tokio::fs::create_dir_all(&staging).await?;
    session.send(b"ready").await?;
    let paths = batch_paths(&manifest, &staging);
    for (item, path) in manifest.items.iter().zip(&paths) {
        let mut file = tokio::fs::File::create(path).await?;
        let mut left = item.size;
        let mut hash = Sha256::new();
        while left > 0 {
            let bytes = session.recv().await?;
            ensure!(
                !bytes.is_empty() && bytes.len() as u64 <= left && bytes.len() <= CHUNK,
                "Invalid file chunk"
            );
            file.write_all(&bytes).await?;
            hash.update(&bytes);
            left -= bytes.len() as u64;
        }
        ensure!(
            hex::encode(hash.finalize()) == item.sha256,
            "File checksum mismatch"
        );
        file.sync_all().await?;
    }
    tokio::fs::write(staging.join("receipt.json"), serde_json::to_vec(&manifest)?).await?;
    tokio::fs::rename(&staging, &dest).await?;
    let paths = batch_paths(&manifest, &dest);
    Ok((manifest, paths))
}
fn batch_paths(manifest: &Manifest, root: &Path) -> Vec<PathBuf> {
    // Numeric prefixes avoid collisions, reserved Windows names, and overwrite attacks.
    manifest
        .items
        .iter()
        .enumerate()
        .map(|(n, i)| root.join(format!("{n:03}_{}", i.name.trim_end_matches(['.', ' ']))))
        .collect()
}
pub async fn acknowledge(session: &mut Session) -> Result<()> {
    session.send(b"committed").await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;
    #[test]
    fn validates_untrusted_metadata() {
        let mut m = Manifest {
            id: transfer_id(),
            text: None,
            items: vec![Item {
                name: "../escape".into(),
                mime: "image/png".into(),
                size: 1,
                sha256: "00".repeat(32),
            }],
        };
        assert!(m.validate().is_err());
        m.items[0].name = "photo.png".into();
        assert!(m.validate().is_ok());
        m.items[0].size = MAX_BYTES + 1;
        assert!(m.validate().is_err());
        assert!(!is_local("8.8.8.8".parse().unwrap()));
        assert!(is_local("192.168.1.4".parse().unwrap()));
    }
    #[tokio::test]
    async fn encrypted_stream_and_retry() -> Result<()> {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/test-data")
            .join(transfer_id());
        tokio::fs::create_dir_all(&root).await?;
        let source = root.join("source");
        let payload = vec![71u8; CHUNK * 3 + 13];
        tokio::fs::write(&source, &payload).await?;
        let m = Manifest {
            id: transfer_id(),
            text: Some("hello clipboard 🦀".into()),
            items: vec![describe(&source, "image.png".into(), "image/png".into()).await?],
        };
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let addr = listener.local_addr()?;
        let destination = root.join("received");
        let server = tokio::spawn(async move {
            for _ in 0..2 {
                let (s, _) = listener.accept().await?;
                let mut s = Session::connect(s, &[9; 32], false).await?;
                let (m, paths) = receive_batch(&mut s, &destination).await?;
                ensure!(
                    m.text.as_deref() == Some("hello clipboard 🦀"),
                    "text mismatch"
                );
                ensure!(
                    tokio::fs::read(&paths[0]).await? == vec![71u8; CHUNK * 3 + 13],
                    "bytes mismatch"
                );
                acknowledge(&mut s).await?;
            }
            Ok::<_, anyhow::Error>(())
        });
        for _ in 0..2 {
            let mut s = Session::connect(TcpStream::connect(addr).await?, &[9; 32], true).await?;
            send_batch(&mut s, &m, &[source.clone()]).await?;
        }
        server.await??;
        // Test-owned scratch is retained for explicit cleanup approval.
        Ok(())
    }
    #[tokio::test]
    async fn wrong_key_is_rejected() -> Result<()> {
        let l = TcpListener::bind("127.0.0.1:0").await?;
        let addr = l.local_addr()?;
        let s = tokio::spawn(async move {
            let (s, _) = l.accept().await.unwrap();
            Session::connect(s, &[1; 32], false).await.is_err()
        });
        let client = Session::connect(TcpStream::connect(addr).await?, &[2; 32], true).await;
        assert!(client.is_err());
        assert!(s.await?);
        Ok(())
    }
    #[tokio::test]
    async fn multiple_originals_and_empty_file() -> Result<()> {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/test-data")
            .join(transfer_id());
        tokio::fs::create_dir_all(&root).await?;
        let a = root.join("a");
        let b = root.join("b");
        tokio::fs::write(&a, b"original-one").await?;
        tokio::fs::write(&b, b"").await?;
        let m = Manifest {
            id: transfer_id(),
            text: None,
            items: vec![
                describe(&a, "same.png".into(), "image/png".into()).await?,
                describe(&b, "same.png".into(), "image/png".into()).await?,
            ],
        };
        let l = TcpListener::bind("127.0.0.1:0").await?;
        let addr = l.local_addr()?;
        let server = tokio::spawn(async move {
            let (s, _) = l.accept().await?;
            let mut s = Session::connect(s, &[7; 32], false).await?;
            let (_, paths) = receive_batch(&mut s, &root.join("in")).await?;
            ensure!(paths[0] != paths[1], "Filename collision");
            ensure!(
                tokio::fs::read(&paths[0]).await? == b"original-one",
                "Original changed"
            );
            ensure!(
                tokio::fs::metadata(&paths[1]).await?.len() == 0,
                "Empty file changed"
            );
            acknowledge(&mut s).await?;
            Ok::<_, anyhow::Error>(())
        });
        let mut s = Session::connect(TcpStream::connect(addr).await?, &[7; 32], true).await?;
        send_batch(&mut s, &m, &[a, b]).await?;
        server.await??;
        Ok(())
    }
    #[tokio::test]
    async fn interrupted_transfer_never_commits() -> Result<()> {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/test-data")
            .join(transfer_id());
        let id = transfer_id();
        let m = Manifest {
            id: id.clone(),
            text: None,
            items: vec![Item {
                name: "video.mp4".into(),
                mime: "video/mp4".into(),
                size: 100,
                sha256: "00".repeat(32),
            }],
        };
        let l = TcpListener::bind("127.0.0.1:0").await?;
        let addr = l.local_addr()?;
        let check = root.clone();
        let server = tokio::spawn(async move {
            let (s, _) = l.accept().await?;
            let mut s = Session::connect(s, &[3; 32], false).await?;
            ensure!(
                receive_batch(&mut s, &root).await.is_err(),
                "Truncated transfer accepted"
            );
            Ok::<_, anyhow::Error>(())
        });
        let mut s = Session::connect(TcpStream::connect(addr).await?, &[3; 32], true).await?;
        s.send(&serde_json::to_vec(&m)?).await?;
        ensure!(s.recv().await? == b"ready", "Not ready");
        s.send(b"short").await?;
        drop(s);
        server.await??;
        ensure!(!check.join(id).exists(), "Partial commit visible");
        Ok(())
    }
    #[tokio::test]
    async fn cache_limit_rejects_before_file_bytes() -> Result<()> {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../target/test-data")
            .join(transfer_id());
        let m = Manifest {
            id: transfer_id(),
            text: None,
            items: vec![Item {
                name: "huge.bin".into(),
                mime: "application/octet-stream".into(),
                size: 100,
                sha256: "00".repeat(32),
            }],
        };
        let l = TcpListener::bind("127.0.0.1:0").await?;
        let addr = l.local_addr()?;
        let server = tokio::spawn(async move {
            let (s, _) = l.accept().await?;
            let mut s = Session::connect(s, &[4; 32], false).await?;
            let err = receive_batch_limited(&mut s, &root, 99).await.unwrap_err();
            ensure!(err.to_string().contains("Cache limit"), "Wrong error");
            Ok::<_, anyhow::Error>(())
        });
        let mut s = Session::connect(TcpStream::connect(addr).await?, &[4; 32], true).await?;
        s.send(&serde_json::to_vec(&m)?).await?;
        ensure!(s.recv().await.is_err(), "Oversize accepted");
        server.await??;
        Ok(())
    }
}
