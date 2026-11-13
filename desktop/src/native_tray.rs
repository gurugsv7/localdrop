//! A single Win32 tray icon, menu and event-only notifications. No helper process.
use anyhow::{ensure, Result};
use std::{
    mem::size_of,
    sync::{
        atomic::{AtomicU32, AtomicUsize, Ordering},
        Mutex, OnceLock,
    },
};
use windows_sys::{
    core::w,
    Win32::{
        Foundation::{HWND, LPARAM, LRESULT, WPARAM},
        Graphics::Gdi::*,
        System::LibraryLoader::GetModuleHandleW,
        System::SystemServices::SS_OWNERDRAW,
        UI::{
            Controls::{DRAWITEMSTRUCT, ODS_SELECTED},
            HiDpi::GetDpiForSystem,
            Shell::*,
            WindowsAndMessaging::*,
        },
    },
};

const ICON_ID: u32 = 1;
const CALLBACK: u32 = WM_APP + 1;
static APP: OnceLock<tauri::AppHandle> = OnceLock::new();
static WINDOW: AtomicUsize = AtomicUsize::new(0);
static ICON: AtomicUsize = AtomicUsize::new(0);
static TASKBAR: AtomicU32 = AtomicU32::new(0);
const NOTICE_MESSAGE: u32 = WM_APP + 2;
static NOTICE_WINDOW: AtomicUsize = AtomicUsize::new(0);
static NOTICE_BRUSH: AtomicUsize = AtomicUsize::new(0);
static TITLE_FONT: AtomicUsize = AtomicUsize::new(0);
static BODY_FONT: AtomicUsize = AtomicUsize::new(0);
static BRAND_FONT: AtomicUsize = AtomicUsize::new(0);
static HINT_FONT: AtomicUsize = AtomicUsize::new(0);
static PASTE_HINT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);
static PENDING: Mutex<Option<(String, String, u32)>> = Mutex::new(None);

fn color(r: u8, g: u8, b: u8) -> u32 {
    r as u32 | (g as u32) << 8 | (b as u32) << 16
}
fn presentation(title: &str, body: &str) -> (&'static str, String, bool) {
    match title {
        "Received and copied" => (
            if body.starts_with("Text") {
                "Text copied"
            } else {
                "Files copied"
            },
            if body.starts_with("Text") {
                "From your phone"
            } else {
                "From your phone"
            }
            .into(),
            true,
        ),
        "Copied to clipboard" => ("Copied to clipboard", "From recent transfers".into(), true),
        "Image copied" => {
            let prefix = body.strip_suffix(" is ready to paste.").unwrap_or("");
            let parts: Vec<_> = prefix.split_whitespace().collect();
            let detail = if parts.len() == 4
                && parts[0] == "Image"
                && parts[2] == "of"
                && parts[1].parse::<u8>().is_ok()
                && parts[3].parse::<u8>().is_ok()
            {
                prefix.to_string()
            } else {
                "Your image is ready".into()
            };
            ("Image copied", detail, true)
        }
        "Sent to Android" => ("Sent to phone", "Transfer complete".into(), false),
        _ => (
            "LocalDrop ready",
            "Your local connection is ready".into(),
            false,
        ),
    }
}
unsafe fn rounded(
    dc: HDC,
    rect: &windows_sys::Win32::Foundation::RECT,
    radius: i32,
    fill: u32,
    border: Option<u32>,
) {
    let brush = CreateSolidBrush(fill);
    let old_brush = SelectObject(dc, brush as HGDIOBJ);
    let pen = border.map(|c| CreatePen(PS_SOLID, 1, c));
    let old_pen = SelectObject(
        dc,
        pen.map(|p| p as HGDIOBJ)
            .unwrap_or_else(|| GetStockObject(NULL_PEN)),
    );
    RoundRect(
        dc,
        rect.left,
        rect.top,
        rect.right,
        rect.bottom,
        radius,
        radius,
    );
    SelectObject(dc, old_pen);
    SelectObject(dc, old_brush);
    DeleteObject(brush as HGDIOBJ);
    if let Some(p) = pen {
        DeleteObject(p as HGDIOBJ);
    }
}
unsafe fn draw_notice(hwnd: HWND) {
    let mut paint: PAINTSTRUCT = std::mem::zeroed();
    let dc = BeginPaint(hwnd, &mut paint);
    let mut area = std::mem::zeroed();
    GetClientRect(hwnd, &mut area);
    let dpi = GetDpiForSystem().max(96) as i32;
    let s = |n: i32| n * dpi / 96;
    let buffer = CreateCompatibleDC(dc);
    let bitmap = CreateCompatibleBitmap(dc, area.right, area.bottom);
    let old_bitmap = SelectObject(buffer, bitmap as HGDIOBJ);
    FillRect(
        buffer,
        &area,
        NOTICE_BRUSH.load(Ordering::Relaxed) as HBRUSH,
    );
    rounded(
        buffer,
        &area,
        s(12),
        color(24, 30, 27),
        Some(color(52, 66, 56)),
    );
    let badge = windows_sys::Win32::Foundation::RECT {
        left: s(14),
        top: s(30),
        right: s(42),
        bottom: s(58),
    };
    rounded(buffer, &badge, s(28), color(36, 68, 48), None);
    let pen = CreatePen(PS_SOLID, s(2).max(2), color(144, 216, 166));
    let old_pen = SelectObject(buffer, pen as HGDIOBJ);
    MoveToEx(buffer, s(21), s(44), std::ptr::null_mut());
    LineTo(buffer, s(26), s(49));
    LineTo(buffer, s(35), s(38));
    SelectObject(buffer, old_pen);
    DeleteObject(pen as HGDIOBJ);
    BitBlt(dc, 0, 0, area.right, area.bottom, buffer, 0, 0, SRCCOPY);
    SelectObject(buffer, old_bitmap);
    DeleteObject(bitmap as HGDIOBJ);
    DeleteDC(buffer);
    EndPaint(hwnd, &paint);
}
unsafe fn draw_item(item: &DRAWITEMSTRUCT) {
    let dpi = GetDpiForSystem().max(96) as i32;
    let s = |n: i32| n * dpi / 96;
    let dc = item.hDC;
    let mut area = item.rcItem;
    FillRect(dc, &area, NOTICE_BRUSH.load(Ordering::Relaxed) as HBRUSH);
    SetBkMode(dc, TRANSPARENT as i32);
    if item.CtlID == 105 {
        if item.itemState & ODS_SELECTED != 0 {
            rounded(dc, &area, s(8), color(44, 58, 47), None);
        }
        let pen = CreatePen(PS_SOLID, s(1).max(1), color(145, 166, 149));
        let old = SelectObject(dc, pen as HGDIOBJ);
        let x = (area.right - area.left) / 2;
        let y = (area.bottom - area.top) / 2;
        let a = s(4);
        MoveToEx(dc, x - a, y - a, std::ptr::null_mut());
        LineTo(dc, x + a, y + a);
        MoveToEx(dc, x + a, y - a, std::ptr::null_mut());
        LineTo(dc, x - a, y + a);
        SelectObject(dc, old);
        DeleteObject(pen as HGDIOBJ);
        return;
    }
    let mut text = [0u16; 256];
    let len = GetWindowTextW(item.hwndItem, text.as_mut_ptr(), 256);
    let font = match item.CtlID {
        101 => BRAND_FONT.load(Ordering::Relaxed),
        102 => TITLE_FONT.load(Ordering::Relaxed),
        104 => HINT_FONT.load(Ordering::Relaxed),
        _ => BODY_FONT.load(Ordering::Relaxed),
    };
    let old = SelectObject(dc, font as HGDIOBJ);
    SetTextColor(
        dc,
        if item.CtlID == 102 {
            color(237, 245, 236)
        } else {
            color(151, 175, 155)
        },
    );
    if item.CtlID == 104 && PASTE_HINT.load(Ordering::Relaxed) {
        let key = windows_sys::Win32::Foundation::RECT {
            left: 0,
            top: s(1),
            right: s(62),
            bottom: s(25),
        };
        rounded(dc, &key, s(8), color(36, 46, 39), Some(color(62, 81, 66)));
        SetTextColor(dc, color(178, 211, 184));
        let mut rect = key;
        DrawTextW(
            dc,
            w!("Ctrl+V"),
            6,
            &mut rect,
            DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX,
        );
    } else {
        DrawTextW(
            dc,
            text.as_ptr(),
            len,
            &mut area,
            DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS,
        );
    }
    SelectObject(dc, old);
}

fn wide<const N: usize>(s: &str) -> [u16; N] {
    let mut buf = [0; N];
    // Keep the trailing zero and never split a surrogate pair.
    let mut n = 0;
    for ch in s.chars() {
        let mut pair = [0; 2];
        let units = ch.encode_utf16(&mut pair);
        if n + units.len() >= N {
            break;
        }
        buf[n..n + units.len()].copy_from_slice(units);
        n += units.len();
    }
    buf
}
fn data() -> NOTIFYICONDATAW {
    let mut d: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
    d.cbSize = size_of::<NOTIFYICONDATAW>() as u32;
    d.hWnd = WINDOW.load(Ordering::Relaxed) as HWND;
    d.uID = ICON_ID;
    d
}
fn add_icon() -> bool {
    let mut d = data();
    d.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    d.uCallbackMessage = CALLBACK;
    d.hIcon = ICON.load(Ordering::Relaxed) as HICON;
    d.szTip = wide("LocalDrop · Share, then paste");
    unsafe { Shell_NotifyIconW(NIM_ADD, &d) != 0 }
}
pub fn notify(title: &str, body: &str) -> bool {
    queue_notice(title, body, 5000)
}
pub fn preview() -> bool {
    queue_notice(
        "Received and copied",
        "Text is ready on your clipboard. Press Ctrl+V.",
        15000,
    )
}
fn queue_notice(title: &str, body: &str, lifetime: u32) -> bool {
    if WINDOW.load(Ordering::Relaxed) == 0 {
        return false;
    }
    *PENDING.lock().unwrap() = Some((
        title.chars().take(60).collect(),
        body.chars().take(200).collect(),
        lifetime,
    ));
    unsafe { PostMessageW(WINDOW.load(Ordering::Relaxed) as HWND, NOTICE_MESSAGE, 0, 0) != 0 }
}
unsafe fn show_notice(title: &str, body: &str, lifetime: u32) {
    let mut state = QUNS_ACCEPTS_NOTIFICATIONS;
    let _ = SHQueryUserNotificationState(&mut state);
    // Never surface private-app UI on a locked/non-active desktop or an exclusive game.
    // Normal full-screen editors are supported: the user explicitly enables these notices.
    if state == QUNS_NOT_PRESENT
        || state == QUNS_RUNNING_D3D_FULL_SCREEN
        || state == QUNS_PRESENTATION_MODE
    {
        return;
    }
    let dpi = GetDpiForSystem().max(96) as i32;
    let scale = |value: i32| value * dpi / 96;
    let mut point = std::mem::zeroed();
    GetCursorPos(&mut point);
    let monitor = MonitorFromPoint(point, MONITOR_DEFAULTTONEAREST);
    let mut info: MONITORINFO = std::mem::zeroed();
    info.cbSize = size_of::<MONITORINFO>() as u32;
    GetMonitorInfoW(monitor, &mut info);
    let width = scale(344);
    let height = scale(86);
    let margin = scale(18);
    let mut hwnd = NOTICE_WINDOW.load(Ordering::Relaxed) as HWND;
    let (heading, detail, paste) = presentation(title, body);
    PASTE_HINT.store(paste, Ordering::Relaxed);
    let caption = wide::<128>(&format!("LocalDrop · {heading}"));
    if hwnd.is_null() {
        hwnd = CreateWindowExW(
            WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
            w!("LocalDropDesktopNotice"),
            caption.as_ptr(),
            WS_POPUP | WS_CLIPCHILDREN,
            info.rcWork.right - width - margin,
            info.rcWork.bottom - height - margin,
            width,
            height,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            GetModuleHandleW(std::ptr::null()),
            std::ptr::null(),
        );
        if hwnd.is_null() {
            return;
        }
        NOTICE_WINDOW.store(hwnd as usize, Ordering::Relaxed);
        for (id, x, y, w, h) in [
            (101, 54, 10, 240, 16),
            (102, 54, 29, 190, 23),
            (103, 54, 54, 260, 18),
            (104, 253, 29, 65, 27),
        ] {
            CreateWindowExW(
                0,
                w!("STATIC"),
                std::ptr::null(),
                WS_CHILD | WS_VISIBLE | SS_OWNERDRAW,
                scale(x),
                scale(y),
                scale(w),
                scale(h),
                hwnd,
                id as HMENU,
                GetModuleHandleW(std::ptr::null()),
                std::ptr::null(),
            );
        }
        CreateWindowExW(
            0,
            w!("BUTTON"),
            w!("Dismiss notification"),
            WS_CHILD | WS_VISIBLE | BS_OWNERDRAW as u32,
            width - scale(28),
            scale(3),
            scale(24),
            scale(24),
            hwnd,
            105usize as HMENU,
            GetModuleHandleW(std::ptr::null()),
            std::ptr::null(),
        );
    }
    SetWindowTextW(hwnd, caption.as_ptr());
    SetWindowTextW(GetDlgItem(hwnd, 101), w!("LOCALDROP"));
    SetWindowTextW(GetDlgItem(hwnd, 102), wide::<128>(heading).as_ptr());
    SetWindowTextW(GetDlgItem(hwnd, 103), wide::<256>(&detail).as_ptr());
    SetWindowTextW(
        GetDlgItem(hwnd, 104),
        if paste {
            w!("Ctrl + V to paste")
        } else {
            w!("Local & encrypted")
        },
    );
    ShowWindow(
        GetDlgItem(hwnd, 104),
        if paste { SW_SHOWNOACTIVATE } else { SW_HIDE },
    );
    let region = CreateRoundRectRgn(0, 0, width + 1, height + 1, scale(12), scale(12));
    if SetWindowRgn(hwnd, region, 1) == 0 {
        DeleteObject(region as HGDIOBJ);
    }
    SetWindowPos(
        hwnd,
        HWND_TOPMOST,
        info.rcWork.right - width - margin,
        info.rcWork.bottom - height - margin,
        width,
        height,
        SWP_NOACTIVATE | SWP_SHOWWINDOW,
    );
    SetTimer(hwnd, 1, lifetime, None);
}
unsafe extern "system" fn notice_procedure(
    hwnd: HWND,
    msg: u32,
    wp: WPARAM,
    lp: LPARAM,
) -> LRESULT {
    match msg {
        WM_ERASEBKGND => 1,
        WM_PAINT => {
            draw_notice(hwnd);
            0
        }
        WM_DRAWITEM => {
            if lp != 0 {
                draw_item(&*(lp as *const DRAWITEMSTRUCT));
            }
            1
        }
        WM_COMMAND if wp & 0xffff == 105 => {
            KillTimer(hwnd, 1);
            DestroyWindow(hwnd);
            NOTICE_WINDOW.store(0, Ordering::Relaxed);
            0
        }
        WM_MOUSEACTIVATE => MA_NOACTIVATE as LRESULT,
        WM_CTLCOLORSTATIC => {
            SetBkColor(wp as HDC, color(24, 30, 27));
            SetTextColor(wp as HDC, color(237, 245, 236));
            NOTICE_BRUSH.load(Ordering::Relaxed) as LRESULT
        }
        WM_TIMER => {
            KillTimer(hwnd, 1);
            DestroyWindow(hwnd);
            NOTICE_WINDOW.store(0, Ordering::Relaxed);
            0
        }
        WM_LBUTTONUP => {
            if let Some(app) = APP.get() {
                super::show(app);
            }
            KillTimer(hwnd, 1);
            DestroyWindow(hwnd);
            NOTICE_WINDOW.store(0, Ordering::Relaxed);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}
unsafe extern "system" fn procedure(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    if msg == NOTICE_MESSAGE {
        let pending = PENDING.lock().unwrap().take();
        if let Some((title, body, lifetime)) = pending {
            show_notice(&title, &body, lifetime);
        }
        return 0;
    }
    if msg == TASKBAR.load(Ordering::Relaxed) && msg != 0 {
        add_icon();
        return 0;
    }
    if msg == CALLBACK {
        if let Some(app) = APP.get() {
            match lp as u32 {
                WM_LBUTTONUP | NIN_BALLOONUSERCLICK => super::show(app),
                WM_RBUTTONUP => {
                    let menu = CreatePopupMenu();
                    AppendMenuW(menu, MF_STRING, 1, w!("Open LocalDrop"));
                    AppendMenuW(menu, MF_STRING, 2, w!("Copy next image"));
                    AppendMenuW(menu, MF_SEPARATOR, 0, std::ptr::null());
                    AppendMenuW(menu, MF_STRING, 3, w!("Quit"));
                    let mut point = std::mem::zeroed();
                    GetCursorPos(&mut point);
                    SetForegroundWindow(hwnd);
                    let command = TrackPopupMenu(
                        menu,
                        TPM_RETURNCMD | TPM_RIGHTBUTTON,
                        point.x,
                        point.y,
                        0,
                        hwnd,
                        std::ptr::null(),
                    );
                    DestroyMenu(menu);
                    PostMessageW(hwnd, WM_NULL, 0, 0);
                    match command {
                        1 => super::show(app),
                        2 => {
                            use tauri::Manager;
                            let s = app.state::<super::State>();
                            if let Err(e) = super::next(s.inner()) {
                                s.lock().unwrap().status = e.to_string();
                            }
                        }
                        3 => app.exit(0),
                        _ => {}
                    }
                }
                _ => {}
            }
        }
        return 0;
    }
    DefWindowProcW(hwnd, msg, wp, lp)
}
pub fn install(app: &tauri::AppHandle) -> Result<()> {
    APP.set(app.clone())
        .map_err(|_| anyhow::anyhow!("Tray already initialized"))?;
    unsafe {
        let instance = GetModuleHandleW(std::ptr::null());
        let brush = CreateSolidBrush(color(24, 30, 27));
        NOTICE_BRUSH.store(brush as usize, Ordering::Relaxed);
        let dpi = GetDpiForSystem().max(96) as i32;
        TITLE_FONT.store(
            CreateFontW(
                -15 * dpi / 96,
                0,
                0,
                0,
                600,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                CLEARTYPE_QUALITY as u32,
                0,
                w!("Segoe UI"),
            ) as usize,
            Ordering::Relaxed,
        );
        BODY_FONT.store(
            CreateFontW(
                -13 * dpi / 96,
                0,
                0,
                0,
                400,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                CLEARTYPE_QUALITY as u32,
                0,
                w!("Segoe UI"),
            ) as usize,
            Ordering::Relaxed,
        );
        BRAND_FONT.store(
            CreateFontW(
                -10 * dpi / 96,
                0,
                0,
                0,
                600,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                CLEARTYPE_QUALITY as u32,
                0,
                w!("Segoe UI"),
            ) as usize,
            Ordering::Relaxed,
        );
        HINT_FONT.store(
            CreateFontW(
                -11 * dpi / 96,
                0,
                0,
                0,
                400,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                CLEARTYPE_QUALITY as u32,
                0,
                w!("Segoe UI"),
            ) as usize,
            Ordering::Relaxed,
        );
        let mut notice: WNDCLASSW = std::mem::zeroed();
        notice.hInstance = instance;
        notice.style = CS_DROPSHADOW;
        notice.lpfnWndProc = Some(notice_procedure);
        notice.lpszClassName = w!("LocalDropDesktopNotice");
        notice.hbrBackground = brush;
        ensure!(
            RegisterClassW(&notice) != 0,
            "Could not register desktop confirmation window"
        );
        let mut class: WNDCLASSW = std::mem::zeroed();
        class.hInstance = instance;
        class.lpfnWndProc = Some(procedure);
        class.lpszClassName = w!("LocalDropNotificationHost");
        ensure!(
            RegisterClassW(&class) != 0,
            "Could not register LocalDrop tray window"
        );
        let hwnd = CreateWindowExW(
            WS_EX_TOOLWINDOW,
            w!("LocalDropNotificationHost"),
            w!("LocalDrop tray"),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            instance,
            std::ptr::null(),
        );
        ensure!(!hwnd.is_null(), "Could not create tray window");
        WINDOW.store(hwnd as usize, Ordering::Relaxed);
        let bytes = include_bytes!("../icons/icon.ico");
        let offset = u32::from_le_bytes(bytes[18..22].try_into().unwrap()) as usize;
        let icon = CreateIconFromResourceEx(
            bytes[offset..].as_ptr(),
            (bytes.len() - offset) as u32,
            1,
            0x30000,
            32,
            32,
            LR_DEFAULTCOLOR,
        );
        ensure!(!icon.is_null(), "Could not create tray icon");
        ICON.store(icon as usize, Ordering::Relaxed);
        TASKBAR.store(
            RegisterWindowMessageW(w!("TaskbarCreated")),
            Ordering::Relaxed,
        );
        ensure!(add_icon(), "Windows rejected the tray icon");
    }
    Ok(())
}
pub fn shutdown() {
    if WINDOW.load(Ordering::Relaxed) == 0 {
        return;
    }
    unsafe {
        let popup = NOTICE_WINDOW.swap(0, Ordering::Relaxed) as HWND;
        if !popup.is_null() {
            DestroyWindow(popup);
        }
        Shell_NotifyIconW(NIM_DELETE, &data());
        DestroyWindow(WINDOW.swap(0, Ordering::Relaxed) as HWND);
        DestroyIcon(ICON.swap(0, Ordering::Relaxed) as HICON);
        DeleteObject(NOTICE_BRUSH.swap(0, Ordering::Relaxed) as HGDIOBJ);
        DeleteObject(TITLE_FONT.swap(0, Ordering::Relaxed) as HGDIOBJ);
        DeleteObject(BODY_FONT.swap(0, Ordering::Relaxed) as HGDIOBJ);
        DeleteObject(BRAND_FONT.swap(0, Ordering::Relaxed) as HGDIOBJ);
        DeleteObject(HINT_FONT.swap(0, Ordering::Relaxed) as HGDIOBJ);
    }
}
