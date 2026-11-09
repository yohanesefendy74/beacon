package com.indocyber.beaconscale;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

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

    // Jenis timbangan (harus sama dengan sketch ESP32)
    private static final int TYPE_MONITOR = 1, TYPE_NON = 2;
    private static final UUID CFG_SERVICE = UUID.fromString("8ec70001-6668-48da-9866-75be8bc86f4d");
    private static final UUID CFG_TYPE    = UUID.fromString("8ec70002-6668-48da-9866-75be8bc86f4d");
    private static final ParcelUuid TYPE_DATA =
            ParcelUuid.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final long APPLY_TIMEOUT_MS = 15_000;

    private RadioGroup rgFilter, rgType;
    private Button btnApply;
    private TextView tvType;

    private int pendingType = 0;        // != 0: sedang mengirim jenis timbangan ke ESP32
    private BluetoothGatt gatt;
    private boolean gattRetried;
    private EditText etFilter;
    private Button btnScan;
    private TextView tvMajor, tvMinor, tvWeight, tvInfo, tvStatus;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

    private Mode mode = Mode.NAME;
    private String targetName;
    private UUID targetUuid;
    private String targetMac;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rgFilter = findViewById(R.id.rgFilter);
        rgType   = findViewById(R.id.rgType);
        btnApply = findViewById(R.id.btnApply);
        tvType   = findViewById(R.id.tvType);
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

        // Simpan nilai tiap mode, supaya ganti pilihan tidak menghapus isian
        rgFilter.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putString(mode.name(), etFilter.getText().toString().trim()).apply();
            mode = modeFromId(id);
            etFilter.setText(prefs.getString(mode.name(), defaultValue(mode)));
            etFilter.setHint(hintFor(mode));
        });
        etFilter.setHint(hintFor(mode));

        btnApply.setOnClickListener(v -> {
            if (scanning || pendingType != 0) { toast("Tunggu proses sebelumnya selesai"); return; }
            pendingType = rgType.getCheckedRadioButtonId() == R.id.rbNon ? TYPE_NON : TYPE_MONITOR;
            if (hasPermissions()) startScan();
            else requestPermissions();
        });

        btnScan.setOnClickListener(v -> {
            if (pendingType != 0) { toast("Sedang menerapkan jenis timbangan..."); return; }
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
        else { pendingType = 0; toast("Izin Bluetooth/Lokasi diperlukan untuk scan"); }
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
        tvStatus.setText(pendingType != 0
                ? "Mencari ESP32 untuk mengirim jenis timbangan..."
                : "Mencari (" + mode.name() + ") ...");
        scanner.startScan(buildFilters(), settings, scanCallback);
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private final Runnable timeoutRunnable = () -> {
        pendingType = 0;
        stopScan("Device tidak ditemukan. Pastikan ESP32 menyala & lokasi/GPS aktif.");
    };

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

            byte[] td = record.getServiceData(TYPE_DATA);
            int type = (td != null && td.length > 0) ? td[0] : 0;

            if (pendingType != 0) {
                BluetoothDevice dev = result.getDevice();
                runOnUiThread(() -> { if (scanning) { stopScan(null); connectAndApply(dev); } });
                return;
            }
            onBeaconFound(name, uuid, mac, major, minor, result.getRssi(), type);
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> stopScan("Scan gagal, kode error " + errorCode));
        }
    };

    // ---------- Hasil ----------
    private void onBeaconFound(String name, UUID uuid, String mac, int major, int minor, int rssi,
                               int type) {
        // nilai = berat x 10 -> Major = byte tinggi, Minor = byte rendah
        // contoh: Major 3, Minor 62 -> 830 -> 83.0 KG
        double weightKg = (major * 256 + minor) / 10.0;

        runOnUiThread(() -> {
            stopScan("Ditemukan (RSSI " + rssi + " dBm)");
            tvMajor.setText(String.format(Locale.US, "Major : %d = hex (%x)", major, major));
            tvMinor.setText(String.format(Locale.US, "Minor : %d = hex (%x)", minor, minor));
            tvWeight.setText("Beratnya = " + formatKg(weightKg) + " KG");
            showType(type);
            tvInfo.setText("Nama : " + (name != null ? name : "-")
                    + "\nUUID : " + uuid.toString().toUpperCase(Locale.US)
                    + "\nMAC  : " + mac);
        });
    }

    private void showType(int type) {
        if (type == TYPE_MONITOR || type == TYPE_NON) {
            tvType.setText("Jenis aktif di ESP32: " + typeName(type));
            rgType.check(type == TYPE_NON ? R.id.rbNon : R.id.rbMonitor);
        } else {
            tvType.setText("Jenis aktif di ESP32: tidak diketahui (firmware ESP32 lama?)");
        }
    }

    private static String typeName(int t) {
        return t == TYPE_NON ? "Non" : "Dengan Monitor";
    }

    // ---------- Kirim jenis timbangan ke ESP32 (GATT) ----------
    @SuppressLint("MissingPermission")
    private void connectAndApply(BluetoothDevice dev) {
        tvStatus.setText("Menghubungkan ke ESP32...");
        btnApply.setEnabled(false);
        handler.postDelayed(applyTimeout, APPLY_TIMEOUT_MS);
        gattRetried = false;
        openGatt(dev);
    }

    @SuppressLint("MissingPermission")
    private void openGatt(BluetoothDevice dev) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        else
            gatt = dev.connectGatt(this, false, gattCallback);
    }

    private final Runnable applyTimeout = () -> finishApply("Gagal: ESP32 tidak merespons. Coba lagi.");

    @SuppressLint("MissingPermission")
    private void finishApply(String status) {
        handler.removeCallbacks(applyTimeout);
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        pendingType = 0;
        btnApply.setEnabled(true);
        tvStatus.setText(status);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && pendingType != 0) {
                BluetoothDevice dev = g.getDevice();
                g.close();
                runOnUiThread(() -> {
                    if (pendingType == 0) return;
                    if (!gattRetried) {               // error 133 sering terjadi, coba sekali lagi
                        gattRetried = true;
                        tvStatus.setText("Mencoba menghubungkan lagi...");
                        openGatt(dev);
                    } else {
                        gatt = null;
                        finishApply("Gagal terhubung ke ESP32 (kode " + status + ")");
                    }
                });
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(CFG_SERVICE);
            BluetoothGattCharacteristic ch = svc != null ? svc.getCharacteristic(CFG_TYPE) : null;
            if (ch == null) {
                runOnUiThread(() -> finishApply(
                        "ESP32 belum mendukung pilihan jenis timbangan. Upload sketch ESP32 terbaru."));
                return;
            }
            byte[] value = {(byte) pendingType};
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        == BluetoothGatt.GATT_SUCCESS;
            } else {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ch.setValue(value);
                ok = g.writeCharacteristic(ch);
            }
            if (!ok) runOnUiThread(() -> finishApply("Gagal mengirim ke ESP32"));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            int t = pendingType;
            runOnUiThread(() -> {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    showType(t);
                    finishApply("Jenis timbangan di ESP32 diubah ke: " + typeName(t));
                } else {
                    finishApply("Gagal mengirim ke ESP32 (kode " + status + ")");
                }
            });
        }
    };

    private static String formatKg(double kg) {
        return (kg == Math.floor(kg)) ? String.valueOf((long) kg)
                : String.format(Locale.US, "%.1f", kg);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override
    protected void onDestroy() {
        stopScan(null);
        if (pendingType != 0) finishApply("");
        super.onDestroy();
    }
}
