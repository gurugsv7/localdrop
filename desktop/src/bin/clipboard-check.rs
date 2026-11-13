#[path = "../clipboard.rs"]
mod clipboard;
use anyhow::{ensure, Result};
use clipboard_win::{raw, Clipboard};
use localdrop_core::{transfer_id, Item, Manifest};
fn main() -> Result<()> {
    let previous = clipboard::read().ok();
    let result = check();
    if let Some((text, paths, bitmap)) = previous {
        let _guard = Clipboard::new_attempts(20)?;
        raw::empty()?;
        if let Some(text) = text {
            raw::set_string_with(&text, clipboard_win::options::NoClear)?;
        }
        if !paths.is_empty() {
            let paths: Vec<_> = paths
                .iter()
                .map(|p| p.to_string_lossy().into_owned())
                .collect();
            raw::set_file_list_with(&paths, clipboard_win::options::NoClear)?;
        }
        if let Some(bytes) = bitmap {
            raw::set_bitmap_with(&bytes, clipboard_win::options::NoClear)?;
        }
    }
    result
}
fn check() -> Result<()> {
    let mut m = Manifest {
        id: transfer_id(),
        text: Some("LocalDrop Unicode 🦀\nhttps://example.com".into()),
        items: vec![],
    };
    clipboard::publish(&m, &[])?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        let mut text = vec![];
        raw::get_string(&mut text)?;
        ensure!(
            String::from_utf8(text)? == m.text.clone().unwrap(),
            "Clipboard text differs"
        );
    }
    println!("PASS: native Unicode clipboard text");
    let root =
        std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../target/clipboard-test-data");
    std::fs::create_dir_all(&root)?;
    let a = root
        .join("one.png")
        .canonicalize()
        .unwrap_or(root.canonicalize()?.join("one.png"));
    let b = root.canonicalize()?.join("two.png");
    let image = image::RgbImage::from_pixel(2, 3, image::Rgb([12, 34, 56]));
    image.save(&a)?;
    image.save(&b)?;
    let item = Item {
        name: "one.png".into(),
        mime: "image/png".into(),
        size: 0,
        sha256: "00".repeat(32),
    };
    m.text = None;
    m.items = vec![item.clone()];
    clipboard::publish(&m, &[a.clone()])?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        ensure!(raw::is_format_avail(8), "CF_DIB missing");
        let mut files = vec![];
        raw::get_file_list(&mut files)?;
        ensure!(files.len() == 1, "File reference missing");
        let mut dib = vec![];
        raw::get_vec(8, &mut dib)?;
        ensure!(
            u32::from_le_bytes(dib[4..8].try_into()?) == 2,
            "Wrong pixel width"
        );
    }
    println!("PASS: actual CF_DIB image pixels plus original file reference");
    m.items.push(item);
    clipboard::publish(&m, &[a.clone(), b])?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        let mut files = vec![];
        raw::get_file_list(&mut files)?;
        ensure!(files.len() == 2, "Multi-file clipboard missing");
    }
    clipboard::next_image(&a)?;
    {
        let _guard = Clipboard::new_attempts(20)?;
        ensure!(raw::is_format_avail(8), "Sequential image missing");
    }
    println!("PASS: multiple CF_HDROP originals and sequential image clipboard");
    Ok(())
}
