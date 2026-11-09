/*
 * ESP32 iBeacon timbangan
 * - Nama device (scan response) : ESP32-KANAN
 * - UUID iBeacon                : BEACON_UUID
 * - Berat lewat Major/Minor     : nilai = berat x 10 ; Major = nilai >> 8, Minor = nilai & 0xFF
 *     contoh 83.0 KG -> 830 -> Major 3, Minor 62
 *
 * JENIS TIMBANGAN (pilih di INPUT_MODE):
 *   INPUT_MONITOR : timbangan DENGAN MONITOR (indikator) -> dibaca dari port RS232 lewat MAX3232
 *   INPUT_NON     : timbangan NON monitor -> load cell langsung lewat modul HX711
 *   INPUT_DEMO    : tes tanpa timbangan, selalu 83 kg
 *
 * Board: ESP32 Dev Module (Arduino core 2.x / 3.x)
 */
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>
#include "scale_parser.h"

// ======================= PENGATURAN =======================
#define INPUT_DEMO         0
#define INPUT_NON          1              // tanpa monitor: load cell + HX711
#define INPUT_MONITOR      2              // dengan monitor: indikator via RS232

#ifndef INPUT_MODE
#define INPUT_MODE   INPUT_MONITOR        // <-- pilih: INPUT_MONITOR atau INPUT_NON
#endif

#define DEVICE_NAME  "ESP32-KANAN"
#define BEACON_UUID  "8EC76EA3-6668-48DA-9866-75BE8BC86F4D"
#define TX_POWER     (-59)
#define UPDATE_MS    200                  // cek berat baru tiap 200 ms
#define ADV_INTERVAL 160                  // broadcast, satuan 0.625 ms -> 160 = 100 ms

// ---- Timbangan DENGAN MONITOR: port RS232 indikator ----
// Samakan dengan setting port serial di indikator (lihat manual indikator).
#define SCALE_RX_PIN      16              // ke TX converter (data masuk dari indikator)
#define SCALE_TX_PIN      17              // ke RX converter (hanya dipakai jika perlu kirim perintah)
#define SCALE_BAUD        9600            // 1200 / 2400 / 4800 / 9600 / 19200
#define SCALE_FRAME       SERIAL_8N1      // umum: SERIAL_8N1 atau SERIAL_7E1
#define SCALE_PROTOCOL    PROTOCOL_GENERIC  // PROTOCOL_REVERSED untuk Yaohua XK3190 mode kontinu
#define SCALE_REQUEST_CMD ""              // isi jika indikator mode "command", mis. "R\r\n"; kosong = kontinu
#define SCALE_REQUEST_MS  300
#define ONLY_STABLE       false           // true: hanya kirim berat saat indikator stabil (bukan "US")
#define SCALE_TIMEOUT_MS  3000            // tidak ada data selama ini -> peringatan di Serial Monitor

// Logika sinyal dibalik? RS232 lewat MAX3232: false (ubah ke true hanya jika data acak/kosong).
#define SCALE_INVERT      false

// ---- Timbangan NON monitor: load cell + HX711 (library "HX711" by Bogdan Necula) ----
#define HX_DOUT 18
#define HX_SCK  19
#define CALIBRATION_FACTOR 21000.0
// ==========================================================

#if INPUT_MODE == INPUT_NON
  #include "HX711.h"
  HX711 scale;
#endif

#if ESP_ARDUINO_VERSION_MAJOR >= 3
  typedef String BleStr;
#else
  typedef std::string BleStr;
#endif

BLEAdvertising *pAdvertising;
uint8_t uuidBytes[16];

static uint8_t hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return 0;
}

void parseUuid(const char *s, uint8_t *out) {
  int n = 0;
  for (int i = 0; s[i] && n < 32; i++) {
    if (s[i] == '-') continue;
    uint8_t v = hexVal(s[i]);
    if (n % 2 == 0) out[n / 2] = v << 4; else out[n / 2] |= v;
    n++;
  }
}

// ---------------- Sumber berat ----------------
float lastKg = 0;
bool  haveKg = false;

#if INPUT_MODE == INPUT_MONITOR
char     lineBuf[64];
uint8_t  lineLen = 0;
uint32_t lastDataMs = 0, lastReqMs = 0, lastWarnMs = 0;

void pollSerialScale() {
  if (strlen(SCALE_REQUEST_CMD) > 0 && millis() - lastReqMs >= SCALE_REQUEST_MS) {
    Serial2.print(SCALE_REQUEST_CMD);
    lastReqMs = millis();
  }
  while (Serial2.available()) {
    char c = (char)Serial2.read();
    // akhir baris: CR, LF, ETX, atau '=' (awal frame Yaohua)
    bool endOfLine = (c == '\r' || c == '\n' || c == 0x03 ||
                      (SCALE_PROTOCOL == PROTOCOL_REVERSED && c == '=' && lineLen > 0));
    if (endOfLine) {
      if (lineLen > 0) {
        lineBuf[lineLen] = 0;
        ScaleReading r = parseScaleLine(lineBuf, SCALE_PROTOCOL);
        if (r.overload) {
          Serial.println("Indikator: OVERLOAD");
        } else if (r.ok && (r.stable || !ONLY_STABLE)) {
          lastKg = r.kg;
          haveKg = true;
          lastDataMs = millis();
        }
      }
      lineLen = 0;
      if (c == '=') lineBuf[lineLen++] = c;   // simpan '=' sebagai awal frame berikutnya
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;  // baris terlalu panjang, buang
    }
  }
  if (haveKg && millis() - lastDataMs > SCALE_TIMEOUT_MS && millis() - lastWarnMs > SCALE_TIMEOUT_MS) {
    Serial.println("PERINGATAN: tidak ada data dari indikator. Cek kabel, baud, frame, invert.");
    lastWarnMs = millis();
  }
}
#endif

float readWeightKg() {
#if INPUT_MODE == INPUT_NON
  if (scale.is_ready()) { lastKg = scale.get_units(1); haveKg = true; }
#elif INPUT_MODE == INPUT_MONITOR
  pollSerialScale();
#else
  lastKg = 83.0; haveKg = true;
#endif
  return lastKg;
}

// ---------------- iBeacon ----------------
void setBeacon(uint16_t major, uint16_t minor) {
  uint8_t p[27];
  int i = 0;
  p[i++] = 26;  p[i++] = 0xFF;          // manufacturer specific data
  p[i++] = 0x4C; p[i++] = 0x00;         // Apple company ID
  p[i++] = 0x02; p[i++] = 0x15;         // iBeacon
  for (int k = 0; k < 16; k++) p[i++] = uuidBytes[k];
  p[i++] = major >> 8;  p[i++] = major & 0xFF;
  p[i++] = minor >> 8;  p[i++] = minor & 0xFF;
  p[i++] = (uint8_t)TX_POWER;

  BleStr payload;
  for (int k = 0; k < i; k++) payload += (char)p[k];

  BLEAdvertisementData advData, scanResp;
  advData.setFlags(0x04);
  advData.addData(payload);
  scanResp.setName(DEVICE_NAME);
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponseData(scanResp);
}

const char *modeName() {
  switch (INPUT_MODE) {
    case INPUT_NON:       return "Non monitor (load cell HX711)";
    case INPUT_MONITOR:   return "Dengan monitor (indikator RS232)";
    default:                return "DEMO (83 kg)";
  }
}

void setup() {
  Serial.begin(115200);
  parseUuid(BEACON_UUID, uuidBytes);

#if INPUT_MODE == INPUT_NON
  scale.begin(HX_DOUT, HX_SCK);
  scale.set_scale(CALIBRATION_FACTOR);
  scale.tare();
#elif INPUT_MODE == INPUT_MONITOR
  Serial2.begin(SCALE_BAUD, SCALE_FRAME, SCALE_RX_PIN, SCALE_TX_PIN, SCALE_INVERT);
#endif

  BLEDevice::init(DEVICE_NAME);
  pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinInterval(ADV_INTERVAL);
  pAdvertising->setMaxInterval(ADV_INTERVAL);
  setBeacon(0, 0);
  pAdvertising->start();

  Serial.println("iBeacon timbangan aktif");
  Serial.print("Sumber : "); Serial.println(modeName());
  Serial.print("Nama   : "); Serial.println(DEVICE_NAME);
  Serial.print("UUID   : "); Serial.println(BEACON_UUID);
  Serial.print("MAC    : "); Serial.println(BLEDevice::getAddress().toString().c_str());
}

uint32_t lastValue = 0xFFFFFFFF;
uint32_t lastUpdate = 0;

void loop() {
  float kg = readWeightKg();     // RS232 dibaca terus agar buffer tidak penuh

  if (millis() - lastUpdate < UPDATE_MS) { delay(5); return; }
  lastUpdate = millis();
  if (!haveKg) return;

  if (kg < 0) kg = 0;            // berat negatif dikirim sebagai 0
  uint32_t v = (uint32_t)(kg * 10.0f + 0.5f);
  if (v > 0xFFFFFF) v = 0xFFFFFF;

  if (v != lastValue) {          // update iklan hanya jika berat berubah
    uint16_t major = v >> 8;
    uint16_t minor = v & 0xFF;
    pAdvertising->stop();
    setBeacon(major, minor);
    pAdvertising->start();
    lastValue = v;
    Serial.printf("Berat %.1f kg -> Major %u, Minor %u\n", kg, major, minor);
  }
}
