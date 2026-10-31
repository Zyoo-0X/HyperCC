# HyperCC 2.5 — AOSP + HyperOS Control Center

Overlay Quick Settings + Notifications gaya **AOSP Android 16** untuk mana-mana telefon Android (diuji untuk Vivo/Funtouch OS).
Bukan ROM dan bukan pengganti SystemUI; ia satu shade yang dilukis di atas skrin melalui Accessibility Service.

## Ciri

- **Gaya Android 16 / Material You**: warna dinamik daripada wallpaper (Android 12+), tile berbentuk pil, slider kecerahan tebal, kad notifikasi berkumpulan dengan sudut bulat.
- **Blur latar** (Android 12+): HyperCC menangkap skrin sekejap sebelum panel dibuka, blur dengan GPU, dan guna sebagai latar. Tangkapan hanya di memori, tidak disimpan atau dihantar. Kekuatan boleh laras. Telefon lama guna scrim gelap.
- **Suis utama**: suis *Enable HyperCC* di atas skrin tetapan. Bila OFF, pemicu leret dibuang, panel dan notifikasi custom tidak keluar, dan shade sistem (juga Quick Settings) dipulihkan seperti biasa. Hidupkan semula untuk kembali ke HyperCC.
- **Ganti shade sistem**: jika shade notifikasi/QS sistem sempat terbuka, HyperCC menutupnya supaya tak perlu tarik dua kali (suis *Replace system shade*).
- **Dua panel ala Android 16**: leret dari tepi atas **kiri = Notifications**, **kanan = Quick Settings**. Panel mengikut jari. Ada mod *satu panel* gabungan.
- **Notifikasi berfungsi**: senarai langsung (kemas kini serta-merta), ikon + nama app, tap untuk buka, leret ke tepi untuk buang, *Clear all*, expand teks penuh / mesej chat, butang action, bar progress, kawalan media, tekan lama untuk tetapan notifikasi app.
- **Customisasi** (buka app HyperCC): tema auto/cerah/gelap, warna accent, bentuk & saiz tile, blur, ketelusan, tukar sisi, tinggi kawasan leret (Small / Normal / Large / XL, lalai Normal), haptic, serta **pilih dan susun tile**. Ada pratonton langsung dan butang *Try*. Butang pensel (edit) di panel Quick Settings membuka mod edit terus di panel: hidup/matikan dan susun tile, bentuk & saiz tile, tema, warna accent, dengan pratonton.
- 18 tile: Internet, Bluetooth, DND, Flashlight, Auto-rotate, Battery Saver, Dark theme, Airplane, Location, Mobile data, serta **Camera off**, **Mic off** (sekat akses camera/mikrofon semua app, Android 12+ dan Shizuku), NFC, Sound mode (normal/getar/senyap), Color inversion, Screenshot, Lock screen, Power menu. Tile tambahan boleh dihidupkan melalui butang pensel atau tetapan. HyperOS menggunakan dua kad rangkaian besar, panel media dengan artwork dan butang Previous/Play-Pause/Next, slider menegak untuk brightness + media volume, dan grid pintasan bulat empat lajur.

## Setup (sekali sahaja)

1. Pasang APK, buka **HyperCC**.
2. **Accessibility service** → hidupkan HyperCC (untuk leret tepi).
3. **Notification access** → hidupkan HyperCC (untuk senarai notifikasi).
4. **Modify system settings** → benarkan (kecerahan, auto-rotate).
5. **Do Not Disturb access** → benarkan (tile DND).
6. (Pilihan) **Shizuku** → supaya tile Wi-Fi, Bluetooth, Airplane, Data, Location, Dark theme dan Battery Saver boleh toggle dari HyperCC. Dalam gaya HyperOS, tile membuka helaian dalaman HyperCC; jika Android menyekat tindakan, helaian itu menerangkan akses yang diperlukan dan tidak terus membuka halaman Wi-Fi/Bluetooth Settings.

> **Penting (Android 13+):** app yang dipasang di luar Play Store disekat daripada Accessibility/Notification access. Jika suis kelabu: *Tetapan → Apps → HyperCC → ⋮ (menu) → Allow restricted settings*, kemudian cuba semula.

## Kebenaran Accessibility

Selain leret tepi, service ini meminta kebenaran *take screenshot* (hanya untuk blur latar) dan menutup shade sistem. Tiada data keluar daripada telefon.

## Had yang perlu diketahui

- Heads-up banner dan lock screen masih dilukis oleh sistem. Panel ini tidak dibuka bila skrin dikunci.
- **Disable system shade** (perlu Shizuku) mematikan terus tarikan shade notifikasi + Quick Settings sistem, jadi hanya panel HyperCC yang keluar. Hidupkan selepas anda pasti panel HyperCC berfungsi. Jika Accessibility service HyperCC dimatikan, shade sistem dipulihkan secara automatik. Tanpa Shizuku, sistem tak boleh dimatikan; hanya *Replace system shade* yang menutupnya selepas ia muncul.
- Pada sesetengah ROM (cth. Vivo), status bar sistem boleh "mencuri" sentuhan di zon paling atas dan menghantar CANCEL. HyperCC kini menganggap itu niat membuka panel dan terus membukanya. Jika masih tidak muncul, naikkan *Swipe area height* satu tahap atau hidupkan *Disable system shade* (Shizuku).
- Sesetengah ROM mematikan app di latar. Kecualikan HyperCC daripada penjimatan bateri jika leret berhenti berfungsi.

## HyperOS Control Center (rebuild 2.6)

- Quick Settings HyperOS menggunakan panel penuh skrin (edge-to-edge dalam kawasan status/navigation bar), bukan kad pendek yang terapung.
- Dua kad rangkaian besar (Wi-Fi + Mobile data) kekal di bahagian atas.
- Panel media membaca Android active media session dan menunjukkan artwork, tajuk/artis serta Previous / Play-Pause / Next. Butang hanya aktif apabila aplikasi pemain menyokong tindakan tersebut. **Notification access** mesti dihidupkan untuk membaca media session.
- Slider kecerahan mengubah `SCREEN_BRIGHTNESS` dan memerlukan kebenaran **Modify system settings**. Slider bunyi mengubah **media volume** (`STREAM_MUSIC`) secara langsung; ia tidak mengubah volume ringtone atau alarm.
- Tile aktif menyala biru dan tile tidak aktif menggunakan bulatan gelap/neutral. Default HyperOS memaparkan lebih banyak shortcut, dan semuanya boleh ditambah, dibuang, atau disusun semula melalui butang pensel **Edit**.
- Menekan Wi-Fi, Mobile data, Bluetooth atau tile lain membuka custom detail sheet HyperCC. Wi-Fi sheet menunjukkan rangkaian semasa, senarai scan yang dibenarkan Android, dan butang refresh; Bluetooth dan Mobile data mempunyai helaian status sendiri. Tile tidak melompat terus ke Settings Android dalam gaya HyperOS.
- Kebenaran Nearby Wi-Fi dan Location boleh diminta dari butang **Allow nearby Wi-Fi access** dalam helaian Wi-Fi. Senarai rangkaian mungkin kosong jika kebenaran ditolak atau Location dimatikan.
- Dalam Edit: long-press tile untuk drag antara Active dan Available atau susun semula; long-press brightness/sound untuk menukar turutan slider. Wi-Fi dan Mobile data ialah kad tetap di bahagian atas.
- Quick Settings dibungkus dalam scroll view; swipe mendatar masih bertukar halaman. Swipe ke atas untuk menutup hanya mengambil alih apabila senarai sudah sampai ke hujung bawah; jika masih ada kandungan untuk ditatal, senarai ditatal dahulu.

## Build APK di GitHub

1. Upload kandungan folder ini ke repo GitHub (termasuk `.github/workflows/build.yml`).
2. Tab **Actions** → **Build APK** → *Run workflow* (atau push ke `main`).
3. Muat turun artifact `HyperCC-debug-apk` dan pasang.

Keperluan: JDK 17, Gradle 8.7, AGP 8.5.2, compileSdk 34, minSdk 26.


## Control Center styles
- AOSP is the default style and preserves the original Android-like layout.
- HyperOS uses the more recognizable Control Center composition: Wi-Fi and mobile-data cards at the top, a media card, vertical brightness/volume sliders, and circular shortcut buttons.
- The HyperOS palette uses neutral dark/light surfaces with Xiaomi blue for active controls. AOSP keeps the Android/Material layout and wallpaper-derived colors.
- In the separated two-panel mode, swipe left on Quick Settings to slide to Notifications; swipe right on Notifications (including a notification card) to return to Quick Settings. Swipe left on a notification card to dismiss it.
- Select the style in Settings > Appearance > Control Center style.

## Build on Windows (automatic setup)

Requirements: Windows 10/11, PowerShell 5.1 or newer, internet access, and enough disk space for the JDK, Android SDK and Gradle downloads. Administrator access is not normally required. The first run downloads tools into `%LOCALAPPDATA%\HyperCC\BuildTools`, outside the project folder, so extracting a new project ZIP does not delete them. Android Studio is not required.

1. Extract the ZIP to a writable folder.
2. Double-click `build.bat`, or open PowerShell in this folder and run `powershell -ExecutionPolicy Bypass -File .\build.ps1`.
3. On the first run, the script downloads a Temurin JDK 17 if needed, Gradle 8.7, Android command-line tools, Android SDK Platform 34 and Build Tools 34.0.0, accepts the SDK licences, and runs `assembleDebug`.
4. When successful, the APK is at `app\build\outputs\apk\debug\app-debug.apk`.

The installer needs an internet connection and downloads software from Eclipse Adoptium, Gradle and Google's Android SDK repository. If Windows Defender or a network policy blocks a download, allow it or install the required component manually and rerun the script. Downloaded tools are kept in `%LOCALAPPDATA%\HyperCC\BuildTools`; generated `local.properties`, `.gradle/` and build output should not be committed to GitHub.

## Build on GitHub

Push the project to a GitHub repository. The included workflow `.github/workflows/build.yml` installs Java 17 and the required Android SDK packages, builds the debug APK, and uploads it as the `HyperCC-debug-apk` workflow artifact. You can start it manually from **Actions → Build APK → Run workflow**.


## Windows: update project without downloading build tools again

The Windows build script stores Java 17, Gradle 8.7, and the Android SDK outside the project folder at `%LOCALAPPDATA%\HyperCC\BuildTools`. These files remain available when you extract a newer project ZIP.

1. First run only: extract the ZIP and run `build.bat`. It downloads required tools and SDK packages once.
2. For future project updates: extract the new ZIP into a **new folder or over the existing project folder**, then run `build.bat`. The cached tools are reused; only missing SDK components are installed. Gradle may still download new project dependencies if the updated source requires dependencies not previously cached.
3. Do not delete `%LOCALAPPDATA%\HyperCC\BuildTools` if you want to keep the cached tools.

An internet connection is required for the first setup and for any new dependencies that have not been cached. GitHub Actions uses a fresh hosted runner unless caching is configured, so this persistent local-tool behavior applies to Windows builds on your own PC.

### GitHub Actions error: `Failed to find package 'tools'`

This project uses `android-actions/setup-android@v4` and explicitly installs only `platform-tools`. The retired standalone Android SDK `tools` package must not be requested. The workflow then installs `platforms;android-34` and `build-tools;34.0.0` with `sdkmanager`. If an older workflow is still running, commit the updated `.github/workflows/build.yml` from this ZIP and rerun the workflow.


## HyperOS 2.6 changes
- HyperOS Control Center now uses a full-height full-screen card with top status-bar and bottom navigation insets. The Quick Settings content scrolls inside the full-screen page.
- Media card height is locked to the same vertical size as the brightness and volume controls in both the live panel and preview.
- Rebuilt the Appearance preview as a tall, phone-like full Control Center mockup: carrier/status row, dual blue network cards, media card, vertical sliders, four-column round tile grid and navigation bar.
- HyperOS tile taps open HyperCC detail sheets for Wi-Fi, Mobile data, Bluetooth and the other controls instead of launching the Android Settings page. The Wi-Fi sheet displays the connected SSID and available scan results when Android permissions allow them.
- Added a runtime permission request entry for Nearby Wi-Fi and Location scanning.
- Android platform limits remain: ordinary apps targeting Android 10+ cannot switch Wi-Fi directly; Shizuku/shell access is required for this custom in-panel toggle. Wi-Fi scan results require the relevant runtime permission and Location conditions.


## HyperOS 2.6 preview and detail screens
- The HyperOS settings preview now uses the saved tile order and visibility from Quick Settings, instead of a separate hard-coded icon list. Its media card, artwork, playback controls and vertical sliders use the same dimensions as the live panel.
- The media card, brightness slider and media-volume slider share a 184dp control-row height. The preview slider width is aligned to the live panel.
- The Wi-Fi main tile displays the connected SSID where Android privacy permissions allow it. Tapping Wi-Fi or any circular tile opens a HyperCC-owned detail sheet; it does not open the stock Wi-Fi/Bluetooth Settings page in HyperOS mode.
- Wi-Fi detail sheet shows current connection, nearby scan results when permitted, an in-app refresh action and an in-app permission request entry. Android still controls whether scan results and direct radio changes are allowed.
- The full-screen HyperOS panel keeps the status-bar and navigation-bar inset areas and scrolls its shortcut list within the panel.
