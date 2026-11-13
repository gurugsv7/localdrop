use super::*;
use anyhow::ensure;
use clipboard_win::{raw, Clipboard};

/// Exercises the production receiver and native clipboard without installing startup entries.
pub async fn run(root: PathBuf) -> Result<()> {
    std::fs::create_dir_all(&root)?;
    let previous = clipboard::read().ok();
    let report = run_checks(root.clone()).await;
    if let Some((text, paths, bitmap)) = previous {
        let _guard = Clipboard::new_attempts(20)?;
        raw::empty()?;
        if let Some(text) = text {
            raw::set_string_with(&text, clipboard_win::options::NoClear)?;
        }
        if !paths.is_empty() {
            let names: Vec<_> = paths
                .iter()
                .map(|p| p.to_string_lossy().into_owned())
                .collect();
            raw::set_file_list_with(&names, clipboard_win::options::NoClear)?;
        }
        if let Some(bytes) = bitmap {
            raw::set_bitmap_with(&bytes, clipboard_win::options::NoClear)?;
        }
    }
    let value = match &report {
        Ok(checks) => serde_json::json!({"passed":checks}),
        Err(e) => serde_json::json!({"failed":format!("{e:#}")}),
    };
    std::fs::write(root.join("result.json"), serde_json::to_vec_pretty(&value)?)?;
    report.map(|_| ())
}
async fn connect(pair: &Pairing) -> Result<Session> {
    let socket = tokio::net::TcpStream::connect(("127.0.0.1", PORT)).await?;
    Session::connect(socket, &pair.key()?, true).await
}
async fn push(pair: &Pairing, m: &Manifest, paths: &[PathBuf]) -> Result<()> {
    let mut s = connect(pair).await?;
    s.send(b"push").await?;
    send_batch(&mut s, m, paths).await
}
async fn run_checks(root: PathBuf) -> Result<Vec<&'static str>> {
    let pair = Pairing::new("127.0.0.1".into());
    let state = Arc::new(Mutex::new(Shared {
        config: Config {
            pairing: pair.clone(),
            cache_mib: 64,
            paired: false,
            device_name: None,
            notifications: false,
        },
        root: root.clone(),
        status: "Diagnostic".into(),
        latest: None,
        next: 0,
    }));
    let server = tauri::async_runtime::spawn(serve(state.clone()));
    tokio::time::sleep(Duration::from_millis(300)).await;
    let mut checks = vec![];
    {
        let mut s = connect(&pair).await?;
        s.send(b"hello").await?;
        s.send(&serde_json::to_vec("Android test phone")?).await?;
        ensure!(s.recv().await? == b"paired", "Pairing not confirmed");
        let saved = state.lock().unwrap();
        ensure!(
            saved.config.paired
                && saved.config.device_name.as_deref() == Some("Android test phone"),
            "Pair status not stored"
        );
    }
    checks.push("Authenticated pairing confirmation persists paired status and phone name");
    let mut m = Manifest {
        id: transfer_id(),
        text: Some("LocalDrop encrypted Unicode 🦀".into()),
        items: vec![],
    };
    push(&pair, &m, &[]).await?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        let mut bytes = vec![];
        raw::get_string(&mut bytes)?;
        ensure!(
            String::from_utf8(bytes)? == m.text.clone().unwrap(),
            "Text was not placed on clipboard"
        );
    }
    checks.push("Actual receiver: authenticated text transfer -> CF_UNICODETEXT");
    let a = root.join("original.png");
    image::RgbImage::from_pixel(2, 3, image::Rgb([12, 34, 56])).save(&a)?;
    m.id = transfer_id();
    m.text = None;
    m.items = vec![describe(&a, "original.png".into(), "image/png".into()).await?];
    push(&pair, &m, &[a.clone()]).await?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        let mut dib = vec![];
        raw::get_vec(8, &mut dib)?;
        ensure!(
            u32::from_le_bytes(dib[4..8].try_into()?) == 2,
            "Image pixels missing"
        );
    }
    checks.push("Actual receiver: streamed single image -> CF_DIB pixels");
    m.id = transfer_id();
    m.items.push(m.items[0].clone());
    push(&pair, &m, &[a.clone(), a.clone()]).await?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        let mut names = vec![];
        raw::get_file_list(&mut names)?;
        ensure!(
            names.len() == 2 && names[0] != names[1],
            "Multiple originals missing"
        );
        for name in names {
            ensure!(
                std::fs::read(name)? == std::fs::read(&a)?,
                "Original changed"
            );
        }
    }
    next(&state)?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        ensure!(raw::is_format_avail(8), "Sequential image copy failed");
    }
    checks.push("Actual receiver: two original images -> CF_HDROP + sequential DIB");
    let reverse = Manifest {
        id: transfer_id(),
        text: Some("Windows to Android protocol".into()),
        items: vec![],
    };
    let folder = root.join("outbox").join(&reverse.id);
    std::fs::create_dir_all(&folder)?;
    std::fs::write(folder.join("receipt.json"), serde_json::to_vec(&reverse)?)?;
    let mut s = connect(&pair).await?;
    s.send(b"pull").await?;
    ensure!(s.recv().await? == b"batch", "No reverse batch");
    let (received, _) = receive_batch(&mut s, &root.join("android-inbox")).await?;
    ensure!(received.text == reverse.text, "Reverse text mismatch");
    acknowledge(&mut s).await?;
    checks.push("Actual receiver: authenticated reverse queue retrieval");
    server.abort();
    Ok(checks)
}
