/*
 * ESP32 iBeacon timbangan
 * - Nama device (scan response) : ESP32-KANAN
 * - UUID iBeacon                : BEACON_UUID
 * - Berat lewat Major/Minor     : nilai = berat x 10 ; Major = nilai >> 8, Minor = nilai & 0xFF
 *     contoh 83.0 KG -> 830 -> Major 3, Minor 62
 *
 * JENIS TIMBANGAN dipilih dari APLIKASI (Android / laptop) lewat Bluetooth:
 *   1 = DENGAN MONITOR : indikator timbangan -> port RS232 -> MAX3232 -> GPIO16
 *   2 = NON monitor    : load cell langsung  -> HX711 (DOUT GPIO18, SCK GPIO19)
 * Pilihan disimpan di flash ESP32 (tetap berlaku setelah mati/nyala).
 * Jenis yang aktif ikut dipancarkan (scan response, service data 0xFFF0) agar tampil di aplikasi.
 *
 * Library: "HX711" by Bogdan Necula. Board: ESP32 Dev Module (Arduino core 2.x / 3.x)
 */
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLEAdvertising.h>
#include <Preferences.h>
#include "HX711.h"
#include "scale_parser.h"

// ======================= PENGATURAN =======================
#define TYPE_MONITOR  1
#define TYPE_NON      2
#define DEFAULT_TYPE  TYPE_MONITOR        // dipakai sebelum aplikasi pernah mengirim pilihan

#ifndef DEMO_MODE
#define DEMO_MODE     false               // true: selalu kirim 83 kg (tes tanpa timbangan)
#endif

#define DEVICE_NAME  "ESP32-KANAN"
#define BEACON_UUID  "8EC76EA3-6668-48DA-9866-75BE8BC86F4D"
#define TX_POWER     (-59)
#define UPDATE_MS    200                  // cek berat baru tiap 200 ms
#define ADV_INTERVAL 160                  // broadcast, satuan 0.625 ms -> 160 = 100 ms

// UUID layanan pengaturan (harus sama dengan aplikasi)
#define CFG_SERVICE_UUID "8ec70001-6668-48da-9866-75be8bc86f4d"
#define CFG_TYPE_UUID    "8ec70002-6668-48da-9866-75be8bc86f4d"
#define TYPE_DATA_UUID   ((uint16_t)0xFFF0)

// ---- Timbangan DENGAN MONITOR: port RS232 indikator ----
#define SCALE_RX_PIN      16              // ke TX MAX3232 (data masuk dari indikator)
#define SCALE_TX_PIN      17              // ke RX MAX3232 (hanya jika perlu kirim perintah)
#define SCALE_BAUD        9600            // samakan dengan indikator
#define SCALE_FRAME       SERIAL_8N1      // umum: SERIAL_8N1 atau SERIAL_7E1
#define SCALE_PROTOCOL    PROTOCOL_GENERIC  // PROTOCOL_REVERSED untuk Yaohua XK3190 mode kontinu
#define SCALE_REQUEST_CMD ""              // isi jika indikator mode "command", mis. "R\r\n"
#define SCALE_REQUEST_MS  300
#define ONLY_STABLE       false
#define SCALE_TIMEOUT_MS  3000
#define SCALE_INVERT      false           // MAX3232: false

// ---- Timbangan NON monitor: load cell + HX711 ----
#define HX_DOUT 18
#define HX_SCK  19
#define CALIBRATION_FACTOR 21000.0
// ==========================================================

#if ESP_ARDUINO_VERSION_MAJOR >= 3
  typedef String BleStr;
#else
  typedef std::string BleStr;
#endif

BLEAdvertising   *pAdvertising;
BLECharacteristic *pTypeChar;
Preferences prefs;
HX711 scale;
uint8_t uuidBytes[16];

uint8_t scaleType = DEFAULT_TYPE;           // jenis aktif
volatile uint8_t requestedType = 0;         // diisi dari aplikasi (task Bluetooth)
volatile bool restartAdv = false;

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

const char *typeName(uint8_t t) {
  return t == TYPE_NON ? "Non monitor (load cell HX711)" : "Dengan monitor (indikator RS232)";
}

// ---------------- Sumber berat ----------------
float lastKg = 0;
bool  haveKg = false;

char     lineBuf[64];
uint8_t  lineLen = 0;
uint32_t lastDataMs = 0, lastReqMs = 0, lastWarnMs = 0;

void pollMonitor() {
  if (strlen(SCALE_REQUEST_CMD) > 0 && millis() - lastReqMs >= SCALE_REQUEST_MS) {
    Serial2.print(SCALE_REQUEST_CMD);
    lastReqMs = millis();
  }
  while (Serial2.available()) {
    char c = (char)Serial2.read();
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
      if (c == '=') lineBuf[lineLen++] = c;
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;
    }
  }
  if (haveKg && millis() - lastDataMs > SCALE_TIMEOUT_MS && millis() - lastWarnMs > SCALE_TIMEOUT_MS) {
    Serial.println("PERINGATAN: tidak ada data dari indikator. Cek kabel, baud, frame.");
    lastWarnMs = millis();
  }
}

void startSource(uint8_t t) {
  Serial2.end();                     // aman walau belum pernah dimulai
  haveKg = false;
  lineLen = 0;
  if (t == TYPE_MONITOR) {
    Serial2.begin(SCALE_BAUD, SCALE_FRAME, SCALE_RX_PIN, SCALE_TX_PIN, SCALE_INVERT);
  } else {
    scale.begin(HX_DOUT, HX_SCK);
    scale.set_scale(CALIBRATION_FACTOR);
    scale.tare();
  }
  Serial.print("Jenis timbangan: "); Serial.println(typeName(t));
}

float readWeightKg() {
  if (DEMO_MODE) { lastKg = 83.0; haveKg = true; return lastKg; }
  if (scaleType == TYPE_MONITOR) pollMonitor();
  else if (scale.is_ready()) { lastKg = scale.get_units(1); haveKg = true; }
  return lastKg;
}

// ---------------- iBeacon ----------------
uint16_t curMajor = 0, curMinor = 0;

void setBeacon(uint16_t major, uint16_t minor) {
  curMajor = major; curMinor = minor;
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
  advData.setFlags(0x06);               // general discoverable, BLE only
  advData.addData(payload);
  scanResp.setName(DEVICE_NAME);
  BleStr typeData; typeData += (char)scaleType;
  scanResp.setServiceData(BLEUUID(TYPE_DATA_UUID), typeData);   // jenis timbangan aktif
  pAdvertising->setAdvertisementData(advData);
  pAdvertising->setScanResponseData(scanResp);
}

void updateAdvertising() {
  pAdvertising->stop();
  setBeacon(curMajor, curMinor);
  pAdvertising->start();
}

// ---------------- Bluetooth: perintah dari aplikasi ----------------
class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override    { restartAdv = true; }  // tetap terlihat oleh HP lain
  void onDisconnect(BLEServer *s) override { restartAdv = true; }
};

class TypeCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    BleStr v = c->getValue();
    if (v.length() >= 1) {
      uint8_t t = (uint8_t)v[0];
      if (t == '1') t = TYPE_MONITOR;       // terima juga teks "1"/"2"
      if (t == '2') t = TYPE_NON;
      if (t == TYPE_MONITOR || t == TYPE_NON) requestedType = t;
    }
  }
};

void setup() {
  Serial.begin(115200);
  parseUuid(BEACON_UUID, uuidBytes);

  prefs.begin("timbangan", false);
  scaleType = prefs.getUChar("type", DEFAULT_TYPE);
  if (scaleType != TYPE_MONITOR && scaleType != TYPE_NON) scaleType = DEFAULT_TYPE;
  startSource(scaleType);

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *svc = server->createService(CFG_SERVICE_UUID);
  pTypeChar = svc->createCharacteristic(CFG_TYPE_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  pTypeChar->setCallbacks(new TypeCb());
  uint8_t tv = scaleType;
  pTypeChar->setValue(&tv, 1);
  svc->start();

  pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinInterval(ADV_INTERVAL);
  pAdvertising->setMaxInterval(ADV_INTERVAL);
  setBeacon(0, 0);
  pAdvertising->start();

  Serial.println("iBeacon timbangan aktif");
  Serial.print("Jenis  : "); Serial.println(DEMO_MODE ? "DEMO (83 kg)" : typeName(scaleType));
  Serial.print("Nama   : "); Serial.println(DEVICE_NAME);
  Serial.print("UUID   : "); Serial.println(BEACON_UUID);
  Serial.print("MAC    : "); Serial.println(BLEDevice::getAddress().toString().c_str());
}

uint32_t lastValue = 0xFFFFFFFF;
uint32_t lastUpdate = 0;

void loop() {
  // pilihan jenis timbangan dari aplikasi
  if (requestedType != 0) {
    uint8_t t = requestedType;
    requestedType = 0;
    if (t != scaleType) {
      scaleType = t;
      prefs.putUChar("type", t);
      startSource(t);
      lastValue = 0xFFFFFFFF;
    }
    uint8_t tv = scaleType;
    pTypeChar->setValue(&tv, 1);
    restartAdv = true;
  }
  if (restartAdv) { restartAdv = false; updateAdvertising(); }

  float kg = readWeightKg();     // RS232 dibaca terus agar buffer tidak penuh
  if (millis() - lastUpdate < UPDATE_MS) { delay(5); return; }
  lastUpdate = millis();
  if (!haveKg) return;

  if (kg < 0) kg = 0;
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
