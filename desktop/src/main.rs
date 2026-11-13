#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod clipboard;
mod native_tray;
#[path = "diagnostic.rs"]
mod receiver_checks;
use anyhow::{Context, Result};
use localdrop_core::*;
use serde::{Deserialize, Serialize};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};
use tauri::Manager;
use tauri_plugin_autostart::ManagerExt;

#[derive(Serialize, Deserialize)]
struct Config {
    pairing: Pairing,
    cache_mib: u64,
    #[serde(default)]
    paired: bool,
    #[serde(default)]
    device_name: Option<String>,
    #[serde(default = "default_on")]
    notifications: bool,
}
fn default_on() -> bool {
    true
}
struct Shared {
    config: Config,
    root: PathBuf,
    status: String,
    latest: Option<(Manifest, Vec<PathBuf>)>,
    next: usize,
}
type State = Arc<Mutex<Shared>>;
#[derive(Serialize)]
struct View {
    status: String,
    cache_mib: u64,
    used_mib: u64,
    history: Vec<Manifest>,
    paired: bool,
    device_name: String,
    notifications: bool,
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
fn history(root: &std::path::Path) -> Vec<Manifest> {
    history_limit(root, 50)
}
fn history_limit(root: &std::path::Path, limit: usize) -> Vec<Manifest> {
    let mut rows: Vec<_> = std::fs::read_dir(root)
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().join("receipt.json").exists())
        .collect();
    rows.sort_by_key(|e| std::cmp::Reverse(e.metadata().and_then(|m| m.modified()).ok()));
    rows.into_iter()
        .take(limit)
        .filter_map(|e| {
            serde_json::from_slice(&std::fs::read(e.path().join("receipt.json")).ok()?).ok()
        })
        .collect()
}
#[tauri::command]
fn status(state: tauri::State<State>) -> View {
    let s = state.lock().unwrap();
    let root = s.root.join("received");
    View {
        status: s.status.clone(),
        cache_mib: s.config.cache_mib,
        used_mib: used(&root) / 1024 / 1024,
        history: history_limit(&root, 3),
        paired: s.config.paired,
        device_name: s
            .config
            .device_name
            .clone()
            .unwrap_or_else(|| "Your Android phone".into()),
        notifications: s.config.notifications,
    }
}
#[tauri::command]
fn pairing_qr(state: tauri::State<State>) -> Result<String, String> {
    let s = state.lock().unwrap();
    qrcode::QrCode::new(serde_json::to_vec(&s.config.pairing).unwrap())
        .map(|q| {
            q.render::<qrcode::render::svg::Color>()
                .min_dimensions(320, 320)
                .build()
        })
        .map_err(|e| e.to_string())
}
#[tauri::command]
fn set_cache(mib: u64, state: tauri::State<State>) -> Result<(), String> {
    if !(64..=65536).contains(&mib) {
        return Err("Choose 64–65536 MiB".into());
    }
    let mut s = state.lock().unwrap();
    s.config.cache_mib = mib;
    save(&s).map_err(|e| e.to_string())
}
#[tauri::command]
fn set_notifications(enabled: bool, state: tauri::State<State>) -> Result<(), String> {
    let mut s = state.lock().unwrap();
    s.config.notifications = enabled;
    save(&s).map_err(|e| e.to_string())
}
#[tauri::command]
fn test_notification(state: tauri::State<State>) -> Result<(), String> {
    if !state.lock().unwrap().config.notifications {
        return Err("Enable notifications first".into());
    }
    if !native_tray::notify(
        "LocalDrop is ready",
        "You’ll see a notification when a share is received and copied.",
    ) {
        return Err("Windows could not accept this notification".into());
    }
    Ok(())
}
#[tauri::command]
fn restore(id: String, state: tauri::State<State>) -> Result<(), String> {
    let mut s = state.lock().unwrap();
    let m = history(&s.root.join("received"))
        .into_iter()
        .find(|m| m.id == id)
        .ok_or("Transfer not found")?;
    let paths = m
        .items
        .iter()
        .enumerate()
        .map(|(n, i)| {
            s.root
                .join("received")
                .join(&id)
                .join(format!("{n:03}_{}", i.name.trim_end_matches(['.', ' '])))
        })
        .collect::<Vec<_>>();
    clipboard::publish(&m, &paths).map_err(|e| e.to_string())?;
    s.latest = Some((m, paths));
    s.next = 0;
    s.status = "Copied to clipboard. Ready to paste.".into();
    if s.config.notifications {
        native_tray::notify(
            "Copied to clipboard",
            "Your transfer is ready. Press Ctrl+V to paste.",
        );
    }
    Ok(())
}
fn next(state: &State) -> Result<()> {
    let mut s = state.lock().unwrap();
    let (m, paths) = s.latest.as_ref().context("No transfer received")?;
    let images: Vec<_> = m
        .items
        .iter()
        .zip(paths)
        .filter(|(i, _)| i.mime.starts_with("image/"))
        .map(|(_, p)| p.clone())
        .collect();
    anyhow::ensure!(!images.is_empty(), "No images in latest transfer");
    let n = s.next % images.len();
    clipboard::next_image(&images[n])?;
    s.next = (n + 1) % images.len();
    s.status = format!(
        "Image {} of {} copied. Paste, then copy next.",
        n + 1,
        images.len()
    );
    if s.config.notifications {
        native_tray::notify(
            "Image copied",
            &format!("Image {} of {} is ready to paste.", n + 1, images.len()),
        );
    }
    Ok(())
}
#[tauri::command]
fn next_image(state: tauri::State<State>) -> Result<(), String> {
    next(&state).map_err(|e| e.to_string())
}
#[tauri::command]
async fn send_clipboard(state: tauri::State<'_, State>) -> Result<(), String> {
    async fn queue(state: &State) -> Result<()> {
        let (text, paths, bitmap) = clipboard::read()?;
        let (root, cap) = {
            let s = state.lock().unwrap();
            (s.root.join("outbox"), s.config.cache_mib * 1024 * 1024)
        };
        let id = transfer_id();
        let folder = root.join(&id);
        tokio::fs::create_dir_all(&folder).await?;
        let mut manifest = Manifest {
            id,
            text,
            items: vec![],
        };
        let mut total = used(&root);
        let mut sources = paths;
        if let Some(bytes) = bitmap {
            let path = folder.join("clipboard.bmp");
            tokio::fs::write(&path, bytes).await?;
            sources.push(path);
        }
        anyhow::ensure!(sources.len() <= MAX_ITEMS, "Too many clipboard files");
        for (n, source) in sources.iter().enumerate() {
            let name = source
                .file_name()
                .context("Missing filename")?
                .to_string_lossy()
                .into_owned();
            let size = tokio::fs::metadata(source).await?.len();
            total = total.checked_add(size).context("Size overflow")?;
            anyhow::ensure!(total <= cap, "Outbox cache limit reached");
            let target = folder.join(format!("{n:03}_{}", name.trim_end_matches(['.', ' '])));
            tokio::fs::copy(source, &target).await?;
            let mime = match source
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_ascii_lowercase()
                .as_str()
            {
                "png" => "image/png",
                "jpg" | "jpeg" => "image/jpeg",
                "bmp" => "image/bmp",
                "webp" => "image/webp",
                _ => "application/octet-stream",
            };
            manifest
                .items
                .push(describe(&target, name, mime.into()).await?);
        }
        manifest.validate()?;
        tokio::fs::write(folder.join("receipt.json"), serde_json::to_vec(&manifest)?).await?;
        state.lock().unwrap().status = "Clipboard queued. Receive on Android to fetch it.".into();
        Ok(())
    }
    queue(state.inner()).await.map_err(|e| e.to_string())
}
fn show(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
        return;
    }
    let _ =
        tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::App("index.html".into()))
            .title("LocalDrop")
            .inner_size(960., 840.)
            .min_inner_size(480., 560.)
            .theme(Some(tauri::Theme::Dark))
            .resizable(true)
            .build();
}

// DPAPI protects the pairing secret for the current Windows user. No plaintext key file.
#[repr(C)]
struct Blob {
    len: u32,
    data: *mut u8,
}
#[link(name = "crypt32")]
unsafe extern "system" {
    fn CryptProtectData(
        input: *const Blob,
        description: *const u16,
        entropy: *const Blob,
        reserved: *mut std::ffi::c_void,
        prompt: *mut std::ffi::c_void,
        flags: u32,
        output: *mut Blob,
    ) -> i32;
    fn CryptUnprotectData(
        input: *const Blob,
        description: *mut *mut u16,
        entropy: *const Blob,
        reserved: *mut std::ffi::c_void,
        prompt: *mut std::ffi::c_void,
        flags: u32,
        output: *mut Blob,
    ) -> i32;
}
#[link(name = "kernel32")]
unsafe extern "system" {
    fn LocalFree(p: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
}
fn protect(bytes: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    let input = Blob {
        len: bytes.len() as u32,
        data: bytes.as_ptr() as *mut u8,
    };
    let mut out = Blob {
        len: 0,
        data: std::ptr::null_mut(),
    };
    unsafe {
        let ok = if encrypt {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                1,
                &mut out,
            )
        } else {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                1,
                &mut out,
            )
        };
        anyhow::ensure!(
            ok != 0,
            "Windows could not access the encrypted pairing configuration"
        );
        let data = std::slice::from_raw_parts(out.data, out.len as usize).to_vec();
        LocalFree(out.data.cast());
        Ok(data)
    }
}
fn save(s: &Shared) -> Result<()> {
    let path = s.root.join("config.bin");
    let tmp = s.root.join("config.new");
    std::fs::write(&tmp, protect(&serde_json::to_vec(&s.config)?, true)?)?;
    std::fs::rename(tmp, path)?;
    Ok(())
}
async fn serve(state: State) -> Result<()> {
    let (pair, root) = {
        let s = state.lock().unwrap();
        (s.config.pairing.clone(), s.root.join("received"))
    };
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", PORT)).await?;
    let discovery = tokio::spawn(advertise(pair.id.clone()));
    if discovery.is_finished() {
        state.lock().unwrap().status = "Discovery failed; use paired address".into();
    }
    // One active transfer bounds RAM, disk contention and clipboard ownership.
    loop {
        let (socket, addr) = listener.accept().await?;
        if !is_local(addr.ip()) {
            continue;
        }
        let cap = state.lock().unwrap().config.cache_mib * 1024 * 1024;
        let result = tokio::time::timeout(Duration::from_secs(3600), async {
            let mut session = Session::connect(socket, &pair.key()?, false).await?;
            {
                let mut s = state.lock().unwrap();
                if !s.config.paired {
                    s.config.paired = true;
                    save(&s)?;
                }
            }
            let mode = session.recv().await?;
            if mode == b"hello" {
                let payload = session.recv().await?;
                let label: String = serde_json::from_slice(&payload)?;
                anyhow::ensure!(
                    !label.is_empty() && label.len() <= 64 && !label.chars().any(char::is_control),
                    "Invalid device name"
                );
                {
                    let mut s = state.lock().unwrap();
                    s.config.device_name = Some(label);
                    s.status = "Paired and ready for your next share".into();
                    save(&s)?;
                }
                session.send(b"paired").await?;
                return Ok(());
            }
            if mode == b"pull" {
                let outbox = { state.lock().unwrap().root.join("outbox") };
                let next = history(&outbox)
                    .into_iter()
                    .rev()
                    .find(|m| !outbox.join(&m.id).join("sent").exists());
                if let Some(m) = next {
                    let folder = outbox.join(&m.id);
                    let paths = m
                        .items
                        .iter()
                        .enumerate()
                        .map(|(n, i)| {
                            folder.join(format!("{n:03}_{}", i.name.trim_end_matches(['.', ' '])))
                        })
                        .collect::<Vec<_>>();
                    session.send(b"batch").await?;
                    send_batch(&mut session, &m, &paths).await?;
                    tokio::fs::write(folder.join("sent"), b"committed").await?;
                    if state.lock().unwrap().config.notifications {
                        native_tray::notify(
                            "Sent to Android",
                            "Your phone received the queued transfer.",
                        );
                    }
                } else {
                    session.send(b"empty").await?;
                }
                return Ok(());
            }
            anyhow::ensure!(mode == b"push", "Unknown operation");
            let (m, paths) =
                receive_batch_limited(&mut session, &root, cap.saturating_sub(used(&root))).await?;
            clipboard::publish(&m, &paths)?;
            {
                let mut s = state.lock().unwrap();
                s.status = if paths.is_empty() {
                    "Text copied. Ready to paste.".into()
                } else if paths.len() == 1 && m.items[0].mime.starts_with("image/") {
                    "Image copied. Ready to paste.".into()
                } else {
                    format!("{} files copied. Ready to paste.", paths.len())
                };
                s.latest = Some((m, paths));
                s.next = 0;
                if s.config.notifications {
                    native_tray::notify(
                        "Received and copied",
                        if s.latest.as_ref().unwrap().1.is_empty() {
                            "Text is ready on your clipboard. Press Ctrl+V."
                        } else {
                            "Your files are ready on the clipboard. Press Ctrl+V."
                        },
                    );
                }
            }
            acknowledge(&mut session).await?;
            Ok::<_, anyhow::Error>(())
        })
        .await;
        match result {
            Ok(Ok(())) => {}
            Ok(Err(e)) => state.lock().unwrap().status = format!("Receive failed: {e}"),
            Err(_) => state.lock().unwrap().status = "Receive timed out; sender may retry".into(),
        }
    }
}
fn main() {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("Async runtime");
    tauri::async_runtime::set(runtime.handle().clone());
    if let Some(pos) = std::env::args().position(|a| a == "--diagnostic") {
        let root = std::env::args()
            .nth(pos + 1)
            .expect("Diagnostic output directory required");
        let result = runtime.block_on(receiver_checks::run(PathBuf::from(root)));
        if let Err(e) = result {
            eprintln!("Diagnostic failed: {e:#}");
            std::process::exit(1);
        }
        return;
    }
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _| {
            if args.iter().any(|a| a == "--preview-notification") {
                native_tray::preview();
            } else {
                show(app);
            }
        }))
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--tray"]),
        ))
        .invoke_handler(tauri::generate_handler![
            status,
            pairing_qr,
            set_cache,
            restore,
            next_image,
            send_clipboard,
            set_notifications,
            test_notification
        ])
        .setup(|app| {
            let root = app.path().app_local_data_dir()?;
            std::fs::create_dir_all(&root)?;
            let fresh = !root.join("config.bin").exists();
            let config = if fresh {
                let socket = std::net::UdpSocket::bind("0.0.0.0:0")?;
                let _ = socket.connect("192.0.2.1:9");
                Config {
                    pairing: Pairing::new(socket.local_addr()?.ip().to_string()),
                    cache_mib: 1024,
                    paired: false,
                    device_name: None,
                    notifications: true,
                }
            } else {
                serde_json::from_slice(&protect(&std::fs::read(root.join("config.bin"))?, false)?)?
            };
            let mut config = config;
            if !config.paired && !history(&root.join("received")).is_empty() {
                config.paired = true;
            }
            let state = Arc::new(Mutex::new(Shared {
                config,
                root,
                status: "Ready on local Wi-Fi".into(),
                latest: None,
                next: 0,
            }));
            save(&state.lock().unwrap())?;
            app.manage(state.clone());
            native_tray::install(app.handle())?;
            if std::env::args().any(|a| a == "--preview-notification") {
                native_tray::preview();
            }
            if let Err(e) = app.autolaunch().enable() {
                state.lock().unwrap().status = format!("Enable startup failed: {e}");
            }
            let s = state.clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = serve(s.clone()).await {
                    s.lock().unwrap().status = format!("Receiver stopped: {e}");
                }
            });
            if fresh || !std::env::args().any(|a| a == "--tray") {
                show(app.handle());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("LocalDrop initialization failed")
        .run(|_, event| {
            if let tauri::RunEvent::Exit = event {
                native_tray::shutdown();
            }
            if let tauri::RunEvent::ExitRequested {
                code: None, api, ..
            } = event
            {
                api.prevent_exit();
            }
        });
}

#[cfg(test)]
mod settings_tests {
    use super::*;
    #[test]
    fn existing_pairing_configuration_migrates_without_losing_trust() {
        let pair = Pairing::new("192.168.1.5".into());
        let old = serde_json::json!({"pairing":pair,"cache_mib":1024});
        let migrated: Config = serde_json::from_value(old).unwrap();
        assert!(migrated.pairing.key().is_ok());
        assert!(migrated.notifications);
        assert!(!migrated.paired);
        assert!(migrated.device_name.is_none());
    }
}
