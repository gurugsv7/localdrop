//! A single authenticated Receive request, with no watcher or persistent text cache.
use crate::clipboard;
use anyhow::{ensure, Context, Result};
use localdrop_core::{describe, transfer_id, Manifest, MAX_BYTES, MAX_ITEMS, MAX_TEXT};
use std::path::PathBuf;
use tokio::io::AsyncWriteExt;

pub struct Snapshot {
    pub manifest: Manifest,
    pub paths: Vec<PathBuf>,
    _bitmap: Option<BitmapSpool>,
}

// Only our generated bitmap is owned. Original file references are never removed.
struct BitmapSpool(PathBuf);
impl Drop for BitmapSpool {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        if let Some(parent) = self.0.parent() {
            let _ = std::fs::remove_dir(parent);
        }
    }
}

pub async fn capture(scratch: PathBuf, cap: u64) -> Result<Option<Snapshot>> {
    let contents = tokio::task::spawn_blocking(clipboard::read).await??;
    prepare(contents, scratch, cap).await
}

async fn prepare(
    (text, mut paths, bitmap): (Option<String>, Vec<PathBuf>, Option<Vec<u8>>),
    scratch: PathBuf,
    cap: u64,
) -> Result<Option<Snapshot>> {
    let text = text.filter(|value| !value.is_empty());
    if text.is_none() && paths.is_empty() && bitmap.is_none() {
        return Ok(None);
    }
    ensure!(
        text.as_ref().map_or(0, |s| s.len()) <= MAX_TEXT,
        "Clipboard text exceeds 32 KiB"
    );
    ensure!(
        paths.len() + usize::from(bitmap.is_some()) <= MAX_ITEMS,
        "Too many clipboard files"
    );
    let cap = cap.min(MAX_BYTES);
    let mut total = bitmap.as_ref().map_or(0, |bytes| bytes.len() as u64);
    ensure!(total <= cap, "Clipboard exceeds transfer limit");
    for source in &paths {
        let metadata = tokio::fs::metadata(source)
            .await
            .context("Clipboard file is unavailable")?;
        ensure!(
            metadata.is_file(),
            "Copy individual files rather than folders"
        );
        total = total.checked_add(metadata.len()).context("Size overflow")?;
        ensure!(total <= cap, "Clipboard exceeds transfer limit");
    }
    let mut snapshot = Snapshot {
        manifest: Manifest {
            id: transfer_id(),
            text,
            items: vec![],
        },
        paths: vec![],
        _bitmap: None,
    };
    if let Some(bytes) = bitmap {
        tokio::fs::create_dir_all(&scratch).await?;
        let folder = scratch.join(&snapshot.manifest.id);
        tokio::fs::create_dir(&folder).await?;
        let file = folder.join("clipboard.bmp");
        snapshot._bitmap = Some(BitmapSpool(file.clone()));
        let mut output = tokio::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&file)
            .await?;
        output.write_all(&bytes).await?;
        output.flush().await?;
        drop(output);
        paths.push(file);
    }
    for source in paths {
        let name = source
            .file_name()
            .context("Missing clipboard filename")?
            .to_string_lossy()
            .into_owned();
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
        snapshot
            .manifest
            .items
            .push(describe(&source, name, mime.into()).await?);
        snapshot.paths.push(source);
    }
    snapshot.manifest.validate()?;
    Ok(Some(snapshot))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn scratch() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../target/test-data")
            .join(transfer_id())
    }
    #[tokio::test]
    async fn text_is_fresh_bounded_and_never_staged_on_disk() {
        let root = scratch();
        let first = prepare((Some("first".into()), vec![], None), root.clone(), 1024)
            .await
            .unwrap()
            .unwrap();
        let second = prepare((Some("second".into()), vec![], None), root.clone(), 1024)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(first.manifest.text.as_deref(), Some("first"));
        assert_eq!(second.manifest.text.as_deref(), Some("second"));
        assert_ne!(first.manifest.id, second.manifest.id);
        assert!(!root.exists());
        assert!(prepare((None, vec![], None), root.clone(), 1024)
            .await
            .unwrap()
            .is_none());
        assert!(
            prepare((Some("x".repeat(MAX_TEXT + 1)), vec![], None), root, 1024)
                .await
                .is_err()
        );
    }
    #[tokio::test]
    async fn original_files_are_streamed_in_place_and_preserved() {
        let root = scratch();
        tokio::fs::create_dir_all(&root).await.unwrap();
        let original = root.join("original.txt");
        tokio::fs::write(&original, b"original bytes")
            .await
            .unwrap();
        let spool = root.join("spool");
        assert!(
            prepare((None, vec![original.clone()], None), spool.clone(), 2)
                .await
                .is_err()
        );
        let snapshot = prepare((None, vec![original.clone()], None), spool.clone(), 1024)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(snapshot.paths, vec![original.clone()]);
        assert_eq!(snapshot.manifest.items[0].size, 14);
        drop(snapshot);
        assert_eq!(tokio::fs::read(original).await.unwrap(), b"original bytes");
        assert!(!spool.exists());
    }
    #[tokio::test]
    async fn bitmap_spool_is_bounded_and_removed_with_snapshot() {
        let root = scratch();
        let bytes = vec![42; 64];
        assert!(
            prepare((None, vec![], Some(bytes.clone())), root.clone(), 2)
                .await
                .is_err()
        );
        assert!(!root.exists());
        let snapshot = prepare((None, vec![], Some(bytes)), root.clone(), 1024)
            .await
            .unwrap()
            .unwrap();
        let generated = snapshot.paths[0].clone();
        assert!(generated.exists());
        drop(snapshot);
        assert!(!generated.exists());
        assert!(std::fs::read_dir(root).unwrap().next().is_none());
    }
}
