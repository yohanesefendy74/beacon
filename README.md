# BeaconScale – iBeacon Scanner (ESP32 -> Android, mode test tanpa database)

## Isi
- `app/`   : proyek Android (Java, minSdk 21 / Android 5.0+)
- `esp32/` : sketch Arduino ESP32 (iBeacon, berat di Major/Minor)

## Cara build APK
**Android Studio:** File > Open > folder `BeaconScale` > tunggu Gradle sync >
Build > Build Bundle(s)/APK(s) > Build APK(s). Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub (tanpa Android Studio):** push folder ini ke repo GitHub; tab Actions >
"Build APK" > unduh artifact `BeaconScale-debug-apk`.

## Langkah
1. Upload `esp32/ESP32_iBeacon_Scale.ino` ke ESP32 (aktifkan `USE_HX711` jika pakai load cell).
   Pilih sumber berat di `INPUT_MODE` (lihat bagian **Sumber berat ESP32**).
2. Install APK, izinkan Bluetooth + Lokasi, nyalakan GPS (wajib untuk BLE scan di Android).
3. Pilih filter **Nama**, **UUID**, atau **MAC**. UUID & MAC ada di Serial Monitor ESP32 (115200),
   atau scan sekali pakai mode Nama: UUID & MAC terdeteksi akan tampil di layar.

## Filter
- **Nama** : butuh scan response, sedikit lebih lambat.
- **UUID** : filter hardware, paling cepat; beri UUID berbeda tiap timbangan.
- **MAC**  : filter hardware, cepat; unik per ESP32.

## Rumus berat
berat (kg) = (Major x 256 + Minor) / 10  ->  Major 3, Minor 62 = 83 KG

## Versi laptop (Windows .exe)
Folder `desktop/` berisi versi Windows (Python + Bleak) dengan tampilan & filter yang sama,
plus opsi **Scan terus (live)** yang menampilkan waktu data pertama dan rata-rata interval update.

- Unduh: tab **Actions** > "Build EXE (Windows)" > artifact `iBeaconScanner-exe`.
- Syarat: Windows 10/11 dengan Bluetooth LE aktif.
- Tanpa exe: `pip install bleak` lalu `python desktop/beacon_scanner.py`.
- Catatan: exe tanpa tanda tangan digital, SmartScreen bisa muncul > "More info" > "Run anyway".

## Sumber berat ESP32 (`INPUT_MODE`)
| Mode | Pakai untuk | Rangkaian ke ESP32 |
|---|---|---|
| `INPUT_DEMO` | tes tanpa timbangan (83 kg) | - |
| `INPUT_HX711` | load cell langsung | HX711 DOUT -> GPIO18, SCK -> GPIO19 |
| `INPUT_RS232` | indikator timbangan port RS232 | Indikator TX -> **MAX3232** -> GPIO16 (RX2) |

**Jangan** sambungkan sinyal RS232 (+/-12 V) langsung ke pin ESP32: wajib lewat converter, dan GND harus tersambung.

Setelan RS232 di sketch (samakan dengan menu komunikasi indikator):
- `SCALE_BAUD` (umumnya 9600), `SCALE_FRAME` (`SERIAL_8N1` atau `SERIAL_7E1`)
- `SCALE_PROTOCOL`: `PROTOCOL_GENERIC` (contoh `ST,GS,+  83.0kg`, `+0083.0`, `WT: 83.0 kg`; satuan kg/g/lb/t otomatis)
  atau `PROTOCOL_REVERSED` (Yaohua XK3190 mode kontinu, mis. `=0.3800`)
- `SCALE_REQUEST_CMD`: kosong = indikator kirim terus (continuous); isi perintah jika indikator mode command
- `ONLY_STABLE`: `true` agar hanya mengirim berat saat indikator stabil
- `SCALE_INVERT`: biarkan `false` untuk MAX3232; ubah ke `true` hanya jika data di Serial Monitor kosong/acak

Workflow **Compile ESP32 sketch** meng-compile ketiga mode dan menyediakan file `.bin` per mode di tab Actions.

