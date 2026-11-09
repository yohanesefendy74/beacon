// Parser data berat dari indikator timbangan (RS232 / Current Loop).
// Tidak bergantung pada Arduino, jadi bisa dites di PC.
#pragma once
#include <string.h>
#include <stdlib.h>
#include <ctype.h>

#define PROTOCOL_GENERIC   0   // contoh: "ST,GS,+  83.0kg", "WT: 83.0 kg", "+0083.0"
#define PROTOCOL_REVERSED  1   // Yaohua XK3190 / sejenis mode kontinu: "=0.3800" (angka terbalik)

struct ScaleReading {
  bool  ok;        // angka berhasil dibaca
  bool  stable;    // false jika indikator kirim tanda "US"/"MOTION"
  bool  overload;  // "OL"/"OVER"
  float kg;
};

static bool containsToken(const char *s, const char *tok) {
  // cari tok sebagai kata utuh (tidak menempel huruf lain), case-insensitive
  size_t n = strlen(tok);
  for (const char *p = s; *p; p++) {
    if (strncasecmp(p, tok, n) == 0) {
      bool leftOk  = (p == s) || !isalpha((unsigned char)p[-1]);
      bool rightOk = !isalpha((unsigned char)p[n]);
      if (leftOk && rightOk) return true;
    }
  }
  return false;
}

static ScaleReading parseScaleLine(const char *line, int protocol) {
  ScaleReading r = {false, true, false, 0};
  char buf[64];
  size_t len = 0;

  // buang karakter kontrol (STX/ETX dsb)
  for (const char *p = line; *p && len < sizeof(buf) - 1; p++)
    if ((unsigned char)*p >= 0x20) buf[len++] = *p;
  buf[len] = 0;
  if (len == 0) return r;

  if (containsToken(buf, "OL") || containsToken(buf, "OVER")) { r.overload = true; return r; }
  if (containsToken(buf, "US") || containsToken(buf, "MOTION")) r.stable = false;

  const char *src = buf;
  char rev[64];
  if (protocol == PROTOCOL_REVERSED) {
    const char *eq = strchr(buf, '=');
    if (!eq) return r;
    size_t n = strlen(eq + 1);
    for (size_t i = 0; i < n; i++) rev[i] = eq[1 + n - 1 - i];
    rev[n] = 0;
    src = rev;
  }

  // cari angka pertama (boleh ada tanda +/- sebelum spasi)
  const char *p = src;
  int sign = 1;
  while (*p && !isdigit((unsigned char)*p)) {
    if (*p == '-') sign = -1;
    else if (*p == '+') sign = 1;
    else if (*p != ' ' && *p != '.') sign = 1;   // tanda hanya berlaku jika menempel/berspasi
    p++;
  }
  if (!*p) return r;

  char num[24];
  size_t k = 0;
  while (*p && k < sizeof(num) - 1 && (isdigit((unsigned char)*p) || *p == '.' || *p == ','))
    num[k++] = (*p == ',') ? '.' : *p, p++;
  num[k] = 0;
  // tanda '-' di belakang angka (beberapa indikator)
  if (*p == '-') sign = -1;

  float v = strtof(num, NULL) * sign;

  // satuan
  const char *u = p;
  while (*u == ' ') u++;
  if (strncasecmp(u, "kg", 2) == 0)       { /* sudah kg */ }
  else if (strncasecmp(u, "lb", 2) == 0)  v *= 0.45359237f;
  else if (strncasecmp(u, "t", 1) == 0 && !isalpha((unsigned char)u[1])) v *= 1000.0f;
  else if (strncasecmp(u, "g", 1) == 0 && !isalpha((unsigned char)u[1])) v /= 1000.0f;

  r.ok = true;
  r.kg = v;
  return r;
}
