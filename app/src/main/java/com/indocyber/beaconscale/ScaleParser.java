package com.indocyber.beaconscale;

import java.util.Locale;

/**
 * Parser data berat dari indikator timbangan (RS232 / Current Loop).
 * Logika sama dengan scale_parser.h di sketch ESP32. Tidak bergantung Android.
 */
public final class ScaleParser {

    public static final int PROTOCOL_GENERIC  = 0; // "ST,GS,+  83.0kg", "+0083.0", "WT: 83.0 kg"
    public static final int PROTOCOL_REVERSED = 1; // Yaohua XK3190 mode kontinu: "=0.3800"

    public static final class Reading {
        public boolean ok;
        public boolean stable = true;
        public boolean overload;
        public double kg;
    }

    private ScaleParser() {}

    private static boolean containsToken(String s, String tok) {
        String up = s.toUpperCase(Locale.US);
        int from = 0, n = tok.length();
        while (true) {
            int i = up.indexOf(tok, from);
            if (i < 0) return false;
            boolean left  = i == 0 || !Character.isLetter(up.charAt(i - 1));
            boolean right = i + n >= up.length() || !Character.isLetter(up.charAt(i + n));
            if (left && right) return true;
            from = i + 1;
        }
    }

    public static Reading parse(String line, int protocol) {
        Reading r = new Reading();
        StringBuilder clean = new StringBuilder();
        for (char c : line.toCharArray()) if (c >= 0x20) clean.append(c);
        String buf = clean.toString();
        if (buf.isEmpty()) return r;

        if (containsToken(buf, "OL") || containsToken(buf, "OVER")) { r.overload = true; return r; }
        if (containsToken(buf, "US") || containsToken(buf, "MOTION")) r.stable = false;

        String src = buf;
        if (protocol == PROTOCOL_REVERSED) {
            int eq = buf.indexOf('=');
            if (eq < 0) return r;
            src = new StringBuilder(buf.substring(eq + 1)).reverse().toString();
        }

        int p = 0, sign = 1, len = src.length();
        while (p < len && !Character.isDigit(src.charAt(p))) {
            char c = src.charAt(p);
            if (c == '-') sign = -1;
            else if (c == '+') sign = 1;
            else if (c != ' ' && c != '.') sign = 1;
            p++;
        }
        if (p >= len) return r;

        StringBuilder num = new StringBuilder();
        while (p < len) {
            char c = src.charAt(p);
            if (Character.isDigit(c) || c == '.') num.append(c);
            else if (c == ',') num.append('.');
            else break;
            p++;
        }
        if (p < len && src.charAt(p) == '-') sign = -1;

        double v;
        try { v = Double.parseDouble(num.toString()) * sign; }
        catch (NumberFormatException e) { return r; }

        String u = src.substring(p).trim().toLowerCase(Locale.US);
        if (u.startsWith("kg")) { /* kg */ }
        else if (u.startsWith("lb")) v *= 0.45359237;
        else if (u.startsWith("t") && (u.length() == 1 || !Character.isLetter(u.charAt(1)))) v *= 1000;
        else if (u.startsWith("g") && (u.length() == 1 || !Character.isLetter(u.charAt(1)))) v /= 1000;

        r.ok = true;
        r.kg = v;
        return r;
    }
}
