/*
 * ESP32 iBeacon timbangan
 * - Nama device (scan response) : ESP32-KANAN
 * - UUID iBeacon                : BEACON_UUID (dikirim persis sesuai teks, tidak terbalik)
 * - MAC address                 : tampil di Serial Monitor saat start
 * - Berat lewat Major/Minor     : nilai = berat x 10 ; Major = nilai >> 8, Minor = nilai & 0xFF
 *     contoh 83.0 KG -> 830 -> Major 3, Minor 62
 * Board: ESP32 Dev Module (Arduino core 2.x / 3.x)
 */
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>

#define DEVICE_NAME  "ESP32-KANAN"
#define BEACON_UUID  "8EC76EA3-6668-48DA-9866-75BE8BC86F4D"   // ganti per timbangan bila perlu
#define TX_POWER     (-59)
#define UPDATE_MS    500     // update nilai berat tiap 500 ms
#define ADV_INTERVAL 800     // interval broadcast, satuan 0.625 ms -> 800 = 500 ms

// ---- Opsional: sensor HX711 (library "HX711" by Bogdan Necula) ----
// #define USE_HX711
#ifdef USE_HX711
  #include "HX711.h"
  #define HX_DOUT 16
  #define HX_SCK  4
  #define CALIBRATION_FACTOR 21000.0
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

// "8EC76EA3-6668-..." -> 16 byte, urutan sama dengan teks (big endian, standar iBeacon)
void parseUuid(const char *s, uint8_t *out) {
  int n = 0;
  for (int i = 0; s[i] && n < 32; i++) {
    if (s[i] == '-') continue;
    uint8_t v = hexVal(s[i]);
    if (n % 2 == 0) out[n / 2] = v << 4; else out[n / 2] |= v;
    n++;
  }
}

float readWeightKg() {
#ifdef USE_HX711
  if (scale.is_ready()) return scale.get_units(3);
  return 0;
#else
  return 83.0;   // nilai contoh
#endif
}

void setBeacon(uint16_t major, uint16_t minor) {
  uint8_t p[27];
  int i = 0;
  p[i++] = 26;            // panjang AD structure
  p[i++] = 0xFF;          // manufacturer specific data
  p[i++] = 0x4C;          // Apple company ID (little endian)
  p[i++] = 0x00;
  p[i++] = 0x02;          // iBeacon type
  p[i++] = 0x15;          // panjang data iBeacon (21)
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

void setup() {
  Serial.begin(115200);
  parseUuid(BEACON_UUID, uuidBytes);
#ifdef USE_HX711
  scale.begin(HX_DOUT, HX_SCK);
  scale.set_scale(CALIBRATION_FACTOR);
  scale.tare();
#endif
  BLEDevice::init(DEVICE_NAME);
  pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinInterval(ADV_INTERVAL);
  pAdvertising->setMaxInterval(ADV_INTERVAL);
  setBeacon(0, 0);
  pAdvertising->start();

  Serial.println("iBeacon timbangan aktif");
  Serial.print("Nama : "); Serial.println(DEVICE_NAME);
  Serial.print("UUID : "); Serial.println(BEACON_UUID);
  Serial.print("MAC  : "); Serial.println(BLEDevice::getAddress().toString().c_str());
}

uint32_t lastValue = 0xFFFFFFFF;

void loop() {
  float kg = readWeightKg();
  if (kg < 0) kg = 0;
  uint32_t v = (uint32_t)(kg * 10.0f + 0.5f);
  if (v > 0xFFFFFF) v = 0xFFFFFF;

  if (v != lastValue) {              // update iklan hanya jika berat berubah
    uint16_t major = v >> 8;
    uint16_t minor = v & 0xFF;
    pAdvertising->stop();
    setBeacon(major, minor);
    pAdvertising->start();
    lastValue = v;
    Serial.printf("Berat %.1f kg -> Major %u, Minor %u\n", kg, major, minor);
  }
  delay(UPDATE_MS);
}
