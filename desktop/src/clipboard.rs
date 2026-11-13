use anyhow::{ensure, Result};
use clipboard_win::{raw, Clipboard};
use localdrop_core::Manifest;
use std::path::{Path, PathBuf};

pub fn image_dib(path: &Path) -> Result<Vec<u8>> {
    let (width, height) = image::ImageReader::open(path)?
        .with_guessed_format()?
        .into_dimensions()?;
    ensure!(
        (width as u64) * (height as u64) <= 16_000_000,
        "Image exceeds 16 megapixel clipboard limit; original retained"
    );
    let mut reader = image::ImageReader::open(path)?.with_guessed_format()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(80 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode()?.into_rgb8();
    let (w, h) = image.dimensions();
    ensure!(
        (w as u64) * (h as u64) <= 16_000_000,
        "Image exceeds 16 megapixel clipboard limit; original retained"
    );
    let stride = (w as usize * 3 + 3) & !3;
    let mut dib = vec![0; 40 + stride * h as usize];
    dib[0..4].copy_from_slice(&40u32.to_le_bytes());
    dib[4..8].copy_from_slice(&(w as i32).to_le_bytes());
    dib[8..12].copy_from_slice(&(h as i32).to_le_bytes());
    dib[12..14].copy_from_slice(&1u16.to_le_bytes());
    dib[14..16].copy_from_slice(&24u16.to_le_bytes());
    for y in 0..h as usize {
        for x in 0..w as usize {
            let p = image.get_pixel(x as u32, y as u32).0;
            let i = 40 + (h as usize - 1 - y) * stride + x * 3;
            dib[i..i + 3].copy_from_slice(&[p[2], p[1], p[0]]);
        }
    }
    Ok(dib)
}
pub fn publish(m: &Manifest, paths: &[PathBuf]) -> Result<()> {
    let dib = if paths.len() == 1 && m.items[0].mime.starts_with("image/") {
        Some(image_dib(&paths[0])?)
    } else {
        None
    };
    let _guard = Clipboard::new_attempts(20)?;
    raw::empty()?;
    if let Some(text) = &m.text {
        raw::set_string_with(text, clipboard_win::options::NoClear)?;
    }
    if !paths.is_empty() {
        let names: Vec<_> = paths
            .iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect();
        raw::set_file_list_with(&names, clipboard_win::options::NoClear)?;
    }
    if let Some(dib) = dib {
        raw::set_without_clear(8, &dib)?;
    }
    Ok(())
}
pub fn next_image(path: &Path) -> Result<()> {
    let dib = image_dib(path)?;
    let _guard = Clipboard::new_attempts(20)?;
    raw::empty()?;
    raw::set_without_clear(8, &dib)?;
    Ok(())
}
pub fn read() -> Result<(Option<String>, Vec<PathBuf>, Option<Vec<u8>>)> {
    let _guard = Clipboard::new_attempts(20)?;
    let mut paths = vec![];
    if raw::is_format_avail(15) {
        raw::get_file_list(&mut paths)?;
        return Ok((None, paths.into_iter().map(PathBuf::from).collect(), None));
    }
    if raw::is_format_avail(8) || raw::is_format_avail(2) {
        let mut bytes = vec![];
        raw::get_bitmap(&mut bytes)?;
        return Ok((None, vec![], Some(bytes)));
    }
    let mut text = vec![];
    raw::get_string(&mut text)?;
    Ok((Some(String::from_utf8(text)?), vec![], None))
}
