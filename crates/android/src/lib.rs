use anyhow::{Context, Result};
use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};
use localdrop_core::*;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
static CANCEL: AtomicBool = AtomicBool::new(false);
async fn cancellable<T>(future: impl std::future::Future<Output = Result<T>>) -> Result<T> {
    CANCEL.store(false, Ordering::Relaxed);
    tokio::select! { result=future=>result, _=async {loop{tokio::time::sleep(std::time::Duration::from_millis(200)).await;if CANCEL.load(Ordering::Relaxed){break;}}}=>anyhow::bail!("Android stopped this job; share remains queued") }
}
#[no_mangle]
pub extern "system" fn Java_app_localdrop_Native_cancel(_: JNIEnv, _: JClass) {
    CANCEL.store(true, Ordering::Relaxed);
}
#[derive(serde::Deserialize)]
struct Draft {
    text: Option<String>,
    files: Vec<DraftFile>,
}
#[derive(serde::Deserialize)]
struct DraftFile {
    path: String,
    name: String,
    mime: String,
}
async fn send(pair: String, folder: String) -> Result<()> {
    let pair: Pairing = serde_json::from_str(&pair)?;
    let folder = PathBuf::from(folder);
    let draft: Draft = serde_json::from_slice(&tokio::fs::read(folder.join("draft.json")).await?)?;
    let mut manifest = Manifest {
        id: folder
            .file_name()
            .context("Missing ID")?
            .to_string_lossy()
            .into_owned(),
        text: draft.text,
        items: vec![],
    };
    let mut paths = vec![];
    for f in draft.files {
        let path = folder.join(&f.path);
        anyhow::ensure!(
            path.starts_with(&folder) && !f.path.contains(['/', '\\']) && f.path != "..",
            "Invalid queued file"
        );
        manifest.items.push(describe(&path, f.name, f.mime).await?);
        paths.push(path);
    }
    let addr = discover(&pair).await?;
    let socket = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        tokio::net::TcpStream::connect(addr),
    )
    .await??;
    let mut session = Session::connect(socket, &pair.key()?, true).await?;
    session.send(b"push").await?;
    send_batch(&mut session, &manifest, &paths).await
}
async fn pull(pair: String, folder: String) -> Result<String> {
    let pair: Pairing = serde_json::from_str(&pair)?;
    let addr = discover(&pair).await?;
    let socket = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        tokio::net::TcpStream::connect(addr),
    )
    .await??;
    let mut session = Session::connect(socket, &pair.key()?, true).await?;
    session.send(b"pull").await?;
    if session.recv().await? == b"empty" {
        return Ok(String::new());
    }
    fn used(path: &std::path::Path) -> u64 {
        std::fs::read_dir(path)
            .into_iter()
            .flatten()
            .flatten()
            .map(|e| {
                if e.file_type().map(|t| t.is_symlink()).unwrap_or(true) {
                    0
                } else if e.path().is_dir() {
                    used(&e.path())
                } else {
                    e.metadata().map(|m| m.len()).unwrap_or(0)
                }
            })
            .sum()
    }
    let root = std::path::Path::new(&folder);
    let available = (1024u64 * 1024 * 1024).saturating_sub(used(root));
    let (manifest, _) = receive_batch_limited(&mut session, root, available).await?;
    acknowledge(&mut session).await?;
    Ok(serde_json::to_string(&manifest)?)
}
async fn confirm(pair: String, device: String) -> Result<()> {
    let pair: Pairing = serde_json::from_str(&pair)?;
    anyhow::ensure!(
        !device.is_empty() && device.len() <= 64,
        "Invalid device name"
    );
    let addr = discover(&pair).await?;
    let socket = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        tokio::net::TcpStream::connect(addr),
    )
    .await??;
    let mut session = Session::connect(socket, &pair.key()?, true).await?;
    session.send(b"hello").await?;
    session.send(&serde_json::to_vec(&device)?).await?;
    anyhow::ensure!(
        session.recv().await? == b"paired",
        "Pairing confirmation failed"
    );
    Ok(())
}
#[no_mangle]
pub extern "system" fn Java_app_localdrop_Native_confirm(
    mut env: JNIEnv,
    _: JClass,
    pair: JString,
    device: JString,
) -> jstring {
    let result = (|| -> Result<()> {
        let pair: String = env.get_string(&pair)?.into();
        let device: String = env.get_string(&device)?.into();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(cancellable(confirm(pair, device)))
    })();
    env.new_string(match result {
        Ok(()) => String::new(),
        Err(e) => format!("{e:#}"),
    })
    .map(|s| s.into_raw())
    .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_app_localdrop_Native_pull(
    mut env: JNIEnv,
    _: JClass,
    pair: JString,
    folder: JString,
) -> jstring {
    let result = (|| -> Result<String> {
        let pair: String = env.get_string(&pair)?.into();
        let folder: String = env.get_string(&folder)?.into();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(cancellable(pull(pair, folder)))
    })();
    let text = match result {
        Ok(s) => s,
        Err(e) => format!("error:{e:#}"),
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_app_localdrop_Native_send(
    mut env: JNIEnv,
    _: JClass,
    pair: JString,
    folder: JString,
) -> jstring {
    let result = (|| -> Result<()> {
        let pair: String = env.get_string(&pair)?.into();
        let folder: String = env.get_string(&folder)?.into();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?
            .block_on(cancellable(send(pair, folder)))
    })();
    let message = match result {
        Ok(()) => String::new(),
        Err(e) => format!("{e:#}"),
    };
    env.new_string(message)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
