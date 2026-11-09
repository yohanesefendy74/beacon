package com.indocyber.beaconscale;

import android.hardware.usb.UsbDeviceConnection;
import android.os.Handler;
import android.os.Looper;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Membaca indikator timbangan lewat kabel USB-serial (OTG).
 * RS232      : kabel USB-RS232 (FTDI / PL2303 / CH340 / CP210x)
 * Current Loop: converter Current Loop 20 mA -> USB (chip yang sama di dalamnya)
 */
public class UsbScaleReader implements SerialInputOutputManager.Listener {

    public interface Callback {
        void onReading(String raw, ScaleParser.Reading reading);
        void onError(String message);
    }

    private final UsbSerialPort port;
    private final int protocol;
    private final byte[] requestCmd;
    private final long requestMs;
    private final Callback cb;

    private SerialInputOutputManager io;
    private final StringBuilder line = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean open;

    public UsbScaleReader(UsbSerialPort port, int protocol, String requestCmd,
                          long requestMs, Callback cb) {
        this.port = port;
        this.protocol = protocol;
        this.requestCmd = (requestCmd == null || requestCmd.isEmpty()) ? null
                : unescape(requestCmd).getBytes(StandardCharsets.US_ASCII);
        this.requestMs = requestMs;
        this.cb = cb;
    }

    public void open(UsbDeviceConnection conn, int baud, int dataBits, int parity) throws IOException {
        port.open(conn);
        port.setParameters(baud, dataBits, UsbSerialPort.STOPBITS_1, parity);
        try { port.setDTR(true); port.setRTS(true); } catch (Exception ignored) {}
        io = new SerialInputOutputManager(port, this);
        io.start();
        open = true;
        if (requestCmd != null) handler.post(requestTask);
    }

    public void close() {
        open = false;
        handler.removeCallbacks(requestTask);
        if (io != null) { io.setListener(null); io.stop(); io = null; }
        try { port.close(); } catch (Exception ignored) {}
    }

    private final Runnable requestTask = new Runnable() {
        @Override public void run() {
            if (!open) return;
            try { port.write(requestCmd, 200); } catch (Exception ignored) {}
            handler.postDelayed(this, requestMs);
        }
    };

    // ---- data masuk (thread background) ----
    @Override
    public void onNewData(byte[] data) {
        for (byte b : data) {
            char c = (char) (b & 0x7F);   // aman untuk 7E1 / 7O1
            boolean eol = c == '\r' || c == '\n' || c == 0x03
                    || (protocol == ScaleParser.PROTOCOL_REVERSED && c == '=' && line.length() > 0);
            if (eol) {
                if (line.length() > 0) {
                    String raw = line.toString();
                    cb.onReading(raw, ScaleParser.parse(raw, protocol));
                }
                line.setLength(0);
                if (c == '=') line.append(c);
            } else if (line.length() < 64) {
                line.append(c);
            } else {
                line.setLength(0);
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        if (open) cb.onError(e.getMessage() != null ? e.getMessage() : "Koneksi USB terputus");
    }

    /** "R\r\n" yang diketik user -> karakter CR LF sungguhan. */
    static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n");
    }
}
