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
