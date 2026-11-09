package com.indocyber.beaconscale;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 101;
    private static final long SCAN_TIMEOUT_MS = 10_000;
    private static final int APPLE_COMPANY_ID = 0x004C;   // iBeacon

    private enum Mode { NAME, UUID, MAC }
    private enum Source { BLE, RS232, CURRENT_LOOP }

    private static final String ACTION_USB_PERMISSION = "com.indocyber.beaconscale.USB_PERMISSION";
    private static final int[] BAUDS = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};
    private static final String[] FRAMES = {"8N1", "7E1", "7O1", "8E1", "8O1"};
    private static final String[] PROTOCOLS = {"Umum (ST,GS,+ 83.0kg / +0083.0)",
            "Terbalik - Yaohua XK3190 (=0.3800)"};

    private RadioGroup rgSource, rgFilter;
    private View panelBle, panelSerial;
    private Spinner spBaud, spFrame, spProtocol;
    private EditText etCmd;
    private CheckBox cbStable;
    private TextView tvSerialHint;
    private EditText etFilter;
    private Button btnScan;
    private TextView tvMajor, tvMinor, tvWeight, tvInfo, tvStatus;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

    private Mode mode = Mode.NAME;
    private Source source = Source.BLE;

    private UsbManager usbManager;
    private UsbScaleReader usbReader;
    private int serialCount = 0;
    private long serialLastMs = 0;
    private String targetName;
    private UUID targetUuid;
    private String targetMac;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rgSource     = findViewById(R.id.rgSource);
        panelBle     = findViewById(R.id.panelBle);
        panelSerial  = findViewById(R.id.panelSerial);
        spBaud       = findViewById(R.id.spBaud);
        spFrame      = findViewById(R.id.spFrame);
        spProtocol   = findViewById(R.id.spProtocol);
        etCmd        = findViewById(R.id.etCmd);
        cbStable     = findViewById(R.id.cbStable);
        tvSerialHint = findViewById(R.id.tvSerialHint);
        rgFilter = findViewById(R.id.rgFilter);
        etFilter = findViewById(R.id.etFilter);
        btnScan  = findViewById(R.id.btnScan);
        tvMajor  = findViewById(R.id.tvMajor);
        tvMinor  = findViewById(R.id.tvMinor);
        tvWeight = findViewById(R.id.tvWeight);
        tvInfo   = findViewById(R.id.tvInfo);
        tvStatus = findViewById(R.id.tvStatus);

        prefs = getSharedPreferences("filter", MODE_PRIVATE);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        setupSerialSettings();
        rgSource.setOnCheckedChangeListener((g, id) -> {
            if (scanning) stopScan(null);
            if (usbReader != null) disconnectUsb(null);
            source = id == R.id.rbRs232 ? Source.RS232
                   : id == R.id.rbCl ? Source.CURRENT_LOOP : Source.BLE;
            prefs.edit().putString("source", source.name()).apply();
            applySourceUi();
        });
        try { source = Source.valueOf(prefs.getString("source", "BLE")); }
        catch (IllegalArgumentException ignored) { source = Source.BLE; }
        rgSource.check(source == Source.RS232 ? R.id.rbRs232
                : source == Source.CURRENT_LOOP ? R.id.rbCl : R.id.rbBle);
        applySourceUi();

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);

        // Simpan nilai tiap mode, supaya ganti pilihan tidak menghapus isian
        rgFilter.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putString(mode.name(), etFilter.getText().toString().trim()).apply();
            mode = modeFromId(id);
            etFilter.setText(prefs.getString(mode.name(), defaultValue(mode)));
            etFilter.setHint(hintFor(mode));
        });
        etFilter.setHint(hintFor(mode));

        btnScan.setOnClickListener(v -> {
            if (source != Source.BLE) {
                if (usbReader != null) disconnectUsb("Terputus");
                else connectUsb();
                return;
            }
            if (scanning) stopScan("Scan dihentikan");
            else if (hasPermissions()) startScan();
            else requestPermissions();
        });
    }

    private Mode modeFromId(int id) {
        if (id == R.id.rbUuid) return Mode.UUID;
        if (id == R.id.rbMac)  return Mode.MAC;
        return Mode.NAME;
    }

    private static String defaultValue(Mode m) {
        switch (m) {
            case UUID: return "8EC76EA3-6668-48DA-9866-75BE8BC86F4D";
            case MAC:  return "";
            default:   return "ESP32-KANAN";
        }
    }

    private static String hintFor(Mode m) {
        switch (m) {
            case UUID: return "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx";
            case MAC:  return "AA:BB:CC:DD:EE:FF";
            default:   return "Nama device, mis. ESP32-KANAN";
        }
    }

    // ---------- Sumber data: tampilan ----------
    private void setupSerialSettings() {
        String[] baudLabels = new String[BAUDS.length];
        for (int i = 0; i < BAUDS.length; i++) baudLabels[i] = String.valueOf(BAUDS[i]);
        spBaud.setAdapter(adapter(baudLabels));
        spFrame.setAdapter(adapter(FRAMES));
        spProtocol.setAdapter(adapter(PROTOCOLS));
        spBaud.setSelection(prefs.getInt("baud", 3));      // 9600
        spFrame.setSelection(prefs.getInt("frame", 0));    // 8N1
        spProtocol.setSelection(prefs.getInt("protocol", 0));
        etCmd.setText(prefs.getString("cmd", ""));
        cbStable.setChecked(prefs.getBoolean("stable", false));
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void applySourceUi() {
        boolean ble = source == Source.BLE;
        panelBle.setVisibility(ble ? View.VISIBLE : View.GONE);
        panelSerial.setVisibility(ble ? View.GONE : View.VISIBLE);
        tvMajor.setVisibility(ble ? View.VISIBLE : View.GONE);
        tvMinor.setVisibility(ble ? View.VISIBLE : View.GONE);
        btnScan.setText(ble ? "Scan" : "Hubungkan");
        tvInfo.setText("");
        tvWeight.setText("Beratnya = - KG");
        if (source == Source.RS232) {
            tvSerialHint.setText("Sambungkan indikator ke HP pakai kabel USB-RS232 + OTG "
                    + "(chip FTDI / PL2303 / CH340 / CP210x).");
        } else if (source == Source.CURRENT_LOOP) {
            tvSerialHint.setText("Sambungkan port Current Loop 20 mA indikator ke HP lewat "
                    + "converter Current Loop -> USB + OTG. Pengaturan baud/frame sama dengan RS232.");
        }
        tvStatus.setText("Siap");
    }

    // ---------- RS232 / Current Loop lewat USB OTG ----------
    private void connectUsb() {
        if (usbManager == null) { toast("HP tidak mendukung USB host (OTG)"); return; }
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            tvStatus.setText("Kabel USB-serial tidak terdeteksi. Cek kabel OTG & aktifkan OTG di pengaturan HP.");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, i, flags));
            tvStatus.setText("Izinkan akses USB, lalu tekan Hubungkan lagi bila perlu.");
            return;
        }
        openUsb(driver);
    }

    private void openUsb(UsbSerialDriver driver) {
        UsbDeviceConnection conn = usbManager.openDevice(driver.getDevice());
        if (conn == null) { tvStatus.setText("Gagal membuka perangkat USB"); return; }

        int baud = BAUDS[spBaud.getSelectedItemPosition()];
        String frame = FRAMES[spFrame.getSelectedItemPosition()];
        int dataBits = frame.charAt(0) == '7' ? 7 : 8;
        int parity = frame.charAt(1) == 'E' ? UsbSerialPort.PARITY_EVEN
                   : frame.charAt(1) == 'O' ? UsbSerialPort.PARITY_ODD : UsbSerialPort.PARITY_NONE;
        int protocol = spProtocol.getSelectedItemPosition() == 1
                ? ScaleParser.PROTOCOL_REVERSED : ScaleParser.PROTOCOL_GENERIC;
        String cmd = etCmd.getText().toString();

        prefs.edit().putInt("baud", spBaud.getSelectedItemPosition())
                .putInt("frame", spFrame.getSelectedItemPosition())
                .putInt("protocol", spProtocol.getSelectedItemPosition())
                .putString("cmd", cmd).putBoolean("stable", cbStable.isChecked()).apply();

        UsbScaleReader reader = new UsbScaleReader(driver.getPorts().get(0), protocol, cmd, 300,
                new UsbScaleReader.Callback() {
                    @Override public void onReading(String raw, ScaleParser.Reading r) {
                        runOnUiThread(() -> showSerialReading(raw, r));
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> disconnectUsb("Terputus: " + message));
                    }
                });
        try {
            reader.open(conn, baud, dataBits, parity);
        } catch (Exception e) {
            reader.close();
            tvStatus.setText("Gagal membuka port: " + e.getMessage());
            return;
        }
        usbReader = reader;
        serialCount = 0;
        serialLastMs = 0;
        btnScan.setText("Putus");
        tvStatus.setText("Terhubung " + driver.getClass().getSimpleName().replace("SerialDriver", "")
                + " " + baud + " " + frame + ". Menunggu data dari indikator...");
    }

    private void showSerialReading(String raw, ScaleParser.Reading r) {
        if (usbReader == null) return;
        long now = System.currentTimeMillis();
        long gap = serialLastMs == 0 ? 0 : now - serialLastMs;
        serialLastMs = now;
        serialCount++;

        String state = r.overload ? "OVERLOAD" : !r.ok ? "Format tidak dikenali"
                : r.stable ? "Stabil" : "Bergerak";
        tvInfo.setText("Data   : " + raw.replaceAll("\\p{Cntrl}", "")
                + "\nStatus : " + state
                + "\nUpdate : ke-" + serialCount + (gap > 0 ? ", " + gap + " ms" : ""));

        if (r.overload) { tvWeight.setText("Beratnya = OVERLOAD"); return; }
        if (!r.ok) return;
        if (cbStable.isChecked() && !r.stable) return;
        tvWeight.setText("Beratnya = " + formatKg(Math.round(r.kg * 100) / 100.0) + " KG");
        tvStatus.setText(source == Source.CURRENT_LOOP ? "Current Loop: menerima data" : "RS232: menerima data");
    }

    private void disconnectUsb(String status) {
        if (usbReader != null) { usbReader.close(); usbReader = null; }
        if (source != Source.BLE) btnScan.setText("Hubungkan");
        if (status != null) tvStatus.setText(status);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectUsb();
                else tvStatus.setText("Akses USB ditolak");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (usbReader != null) disconnectUsb("Kabel USB dicabut");
            }
        }
    };

    // ---------- Permission ----------
    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        for (String p : requiredPermissions())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            requestPermissions(requiredPermissions(), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM && hasPermissions()) startScan();
        else toast("Izin Bluetooth/Lokasi diperlukan untuk scan");
    }

    // ---------- Validasi input ----------
    private boolean readTarget() {
        String v = etFilter.getText().toString().trim();
        prefs.edit().putString(mode.name(), v).apply();
        switch (mode) {
            case UUID:
                try { targetUuid = UUID.fromString(v); return true; }
                catch (IllegalArgumentException e) { toast("Format UUID salah"); return false; }
            case MAC:
                v = v.toUpperCase(Locale.US).replace('-', ':');
                if (!BluetoothAdapter.checkBluetoothAddress(v)) {
                    toast("Format MAC salah, contoh AA:BB:CC:DD:EE:FF"); return false;
                }
                targetMac = v; return true;
            default:
                if (v.isEmpty()) { toast("Isi nama device"); return false; }
                targetName = v; return true;
        }
    }

    /** Filter hardware (lebih cepat & hemat baterai) untuk UUID dan MAC. */
    private List<ScanFilter> buildFilters() {
        List<ScanFilter> list = new ArrayList<>();
        if (mode == Mode.MAC) {
            list.add(new ScanFilter.Builder().setDeviceAddress(targetMac).build());
        } else if (mode == Mode.UUID) {
            ByteBuffer bb = ByteBuffer.allocate(18);
            bb.put((byte) 0x02).put((byte) 0x15);
            bb.putLong(targetUuid.getMostSignificantBits());
            bb.putLong(targetUuid.getLeastSignificantBits());
            byte[] data = bb.array();
            byte[] mask = new byte[18];
            java.util.Arrays.fill(mask, (byte) 0xFF);
            list.add(new ScanFilter.Builder()
                    .setManufacturerData(APPLE_COMPANY_ID, data, mask).build());
        }
        return list.isEmpty() ? null : list;   // mode Nama: difilter di software
    }

    // ---------- BLE Scan ----------
    @SuppressLint("MissingPermission")
    private void startScan() {
        if (btAdapter == null) { toast("HP tidak mendukung Bluetooth"); return; }
        if (!btAdapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) { toast("BLE scanner tidak tersedia"); return; }
        if (!readTarget()) return;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        btnScan.setText("Stop");
        tvStatus.setText("Mencari (" + mode.name() + ") ...");
        scanner.startScan(buildFilters(), settings, scanCallback);
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private final Runnable timeoutRunnable = () ->
            stopScan("Device tidak ditemukan. Pastikan ESP32 menyala & lokasi/GPS aktif.");

    @SuppressLint("MissingPermission")
    private void stopScan(String status) {
        handler.removeCallbacks(timeoutRunnable);
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        btnScan.setText("Scan");
        if (status != null) tvStatus.setText(status);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null || !scanning) return;

            byte[] m = record.getManufacturerSpecificData(APPLE_COMPANY_ID);
            // iBeacon: 02 15 | UUID(16) | Major(2) | Minor(2) | TxPower(1)
            if (m == null || m.length < 23 || m[0] != 0x02 || m[1] != 0x15) return;

            ByteBuffer bb = ByteBuffer.wrap(m, 2, 16);
            UUID uuid = new UUID(bb.getLong(), bb.getLong());
            String mac = result.getDevice().getAddress();
            String name = record.getDeviceName();
            if (name == null) {
                try { name = result.getDevice().getName(); } catch (SecurityException ignored) {}
            }

            boolean match;
            switch (mode) {
                case UUID: match = uuid.equals(targetUuid); break;
                case MAC:  match = mac.equalsIgnoreCase(targetMac); break;
                default:   match = name != null && name.equalsIgnoreCase(targetName);
            }
            if (!match) return;

            int major = ((m[18] & 0xFF) << 8) | (m[19] & 0xFF);
            int minor = ((m[20] & 0xFF) << 8) | (m[21] & 0xFF);
            onBeaconFound(name, uuid, mac, major, minor, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> stopScan("Scan gagal, kode error " + errorCode));
        }
    };

    // ---------- Hasil ----------
    private void onBeaconFound(String name, UUID uuid, String mac, int major, int minor, int rssi) {
        // nilai = berat x 10 -> Major = byte tinggi, Minor = byte rendah
        // contoh: Major 3, Minor 62 -> 830 -> 83.0 KG
        double weightKg = (major * 256 + minor) / 10.0;

        runOnUiThread(() -> {
            stopScan("Ditemukan (RSSI " + rssi + " dBm)");
            tvMajor.setText(String.format(Locale.US, "Major : %d = hex (%x)", major, major));
            tvMinor.setText(String.format(Locale.US, "Minor : %d = hex (%x)", minor, minor));
            tvWeight.setText("Beratnya = " + formatKg(weightKg) + " KG");
            tvInfo.setText("Nama : " + (name != null ? name : "-")
                    + "\nUUID : " + uuid.toString().toUpperCase(Locale.US)
                    + "\nMAC  : " + mac);
        });
    }

    private static String formatKg(double kg) {
        // 83 -> "83", 83.5 -> "83.5", 82.998 -> "83", 1.25 -> "1.25"
        return new java.math.BigDecimal(kg).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        stopScan(null);
        disconnectUsb(null);
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
