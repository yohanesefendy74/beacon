"""iBeacon Scanner untuk laptop Windows (BLE), versi desktop dari aplikasi Android.

Butuh laptop dengan Bluetooth 4.0+ (BLE) dan Windows 10/11.
Jalankan langsung:  python beacon_scanner.py
Build exe:          pyinstaller lihat build-exe.yml
"""
import asyncio
import queue
import threading
import time
import tkinter as tk
from tkinter import messagebox

from bleak import BleakClient, BleakScanner

import beacon_core as core

BG = "#AECBDD"
SCAN_TIMEOUT_S = 10

# Jenis timbangan (harus sama dengan sketch ESP32)
TYPE_MONITOR, TYPE_NON = 1, 2
TYPE_NAMES = {TYPE_MONITOR: "Dengan Monitor", TYPE_NON: "Non"}
CFG_TYPE_UUID = "8ec70002-6668-48da-9866-75be8bc86f4d"
TYPE_DATA_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"

DEFAULTS = {"NAMA": "ESP32-KANAN",
            "UUID": "8EC76EA3-6668-48DA-9866-75BE8BC86F4D",
            "MAC": ""}


class App:
    def __init__(self, root):
        self.root = root
        root.title("iBeacon Scanner")
        root.configure(bg=BG)
        root.geometry("420x660")
        root.resizable(False, True)

        self.mode = tk.StringVar(value="NAMA")
        self._cur_mode = "NAMA"
        self.first_ms = 0.0
        self.values = dict(DEFAULTS)
        self.live = tk.BooleanVar(value=False)
        self.scale_type = tk.IntVar(value=TYPE_MONITOR)
        self.applying = False

        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self.scan_future = None
        self.stop_event = None
        self.scanning = False

        self.hits = 0
        self.last_hit = None
        self.intervals = []

        self.q = queue.Queue()
        self._build_ui()
        self._poll()

    def ui(self, fn, *args):
        """Panggil fungsi UI dari thread Bluetooth (aman untuk Tkinter)."""
        self.q.put((fn, args))

    def _poll(self):
        try:
            while True:
                fn, args = self.q.get_nowait()
                fn(*args)
        except queue.Empty:
            pass
        self.root.after(30, self._poll)

    # ---------- UI ----------
    def _build_ui(self):
        f = tk.Frame(self.root, bg=BG, padx=20, pady=16)
        f.pack(fill="both", expand=True)

        bold = ("Segoe UI", 13, "bold")
        tk.Label(f, text="iBEACON SCANNER FINAL", font=bold, bg=BG).pack(anchor="w")

        tk.Label(f, text="Filter berdasarkan:", bg=BG).pack(anchor="w", pady=(14, 0))
        rf = tk.Frame(f, bg=BG)
        rf.pack(anchor="w")
        for m in ("NAMA", "UUID", "MAC"):
            tk.Radiobutton(rf, text=m.title() if m == "NAMA" else m, value=m,
                           variable=self.mode, bg=BG, activebackground=BG,
                           command=self._mode_changed).pack(side="left", padx=(0, 12))

        self.entry = tk.Entry(f, font=("Segoe UI", 11), width=40)
        self.entry.pack(anchor="w", pady=(6, 0), fill="x")
        self.entry.insert(0, self.values["NAMA"])
        self.hint = tk.Label(f, text="Nama device, mis. ESP32-KANAN", bg=BG,
                             fg="#555555", font=("Segoe UI", 8))
        self.hint.pack(anchor="w")

        tk.Label(f, text="Jenis timbangan:", font=("Segoe UI", 9, "bold"), bg=BG
                 ).pack(anchor="w", pady=(12, 0))
        tf = tk.Frame(f, bg=BG)
        tf.pack(anchor="w", fill="x")
        for t in (TYPE_MONITOR, TYPE_NON):
            tk.Radiobutton(tf, text=TYPE_NAMES[t], value=t, variable=self.scale_type,
                           bg=BG, activebackground=BG).pack(side="left", padx=(0, 12))
        self.btn_apply = tk.Button(tf, text="Terapkan ke ESP32", command=self.apply_type)
        self.btn_apply.pack(side="right")
        self.lbl_type = tk.Label(f, text="Jenis aktif di ESP32: - (tekan Scan)", bg=BG,
                                 fg="#333333", font=("Segoe UI", 9))
        self.lbl_type.pack(anchor="w")

        self.btn = tk.Button(f, text="Scan", width=12, height=4, command=self.toggle)
        self.btn.pack(pady=14)

        big = ("Segoe UI", 13, "bold")
        self.lbl_major = tk.Label(f, text="Major : -", font=big, bg=BG)
        self.lbl_major.pack(anchor="w")
        self.lbl_minor = tk.Label(f, text="Minor : -", font=big, bg=BG)
        self.lbl_minor.pack(anchor="w")
        self.lbl_weight = tk.Label(f, text="Beratnya = - KG",
                                   font=("Segoe UI", 16, "bold"), bg=BG)
        self.lbl_weight.pack(anchor="w", pady=(18, 0))

        self.info = tk.Text(f, height=4, width=46, font=("Consolas", 9),
                            bg=BG, relief="flat")
        self.info.pack(anchor="w", pady=(14, 0))
        self.info.configure(state="disabled")

        self.status = tk.Label(f, text="Siap scan", bg=BG, fg="#333333",
                               wraplength=370, justify="left")
        self.status.pack(anchor="w", pady=(8, 0))

    def _mode_changed(self):
        # simpan isian mode lama, tampilkan isian mode baru
        self.values[self._cur_mode] = self.entry.get().strip()
        self._cur_mode = self.mode.get()
        self.entry.delete(0, "end")
        self.entry.insert(0, self.values[self._cur_mode])
        self.hint.config(text={
            "NAMA": "Nama device, mis. ESP32-KANAN",
            "UUID": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
            "MAC": "AA:BB:CC:DD:EE:FF"}[self._cur_mode])

    def set_info(self, text):
        self.info.configure(state="normal")
        self.info.delete("1.0", "end")
        self.info.insert("1.0", text)
        self.info.configure(state="disabled")

    # ---------- Scan ----------
    def toggle(self):
        if self.applying:
            return
        if self.scanning:
            self.stop("Scan dihentikan")
            return
        mode = self.mode.get()
        mode_core = "NAME" if mode == "NAMA" else mode
        target, err = core.normalize_target(mode_core, self.entry.get())
        if err:
            messagebox.showwarning("Input", err)
            return
        self.values[mode] = self.entry.get().strip()

        self.hits, self.last_hit, self.intervals = 0, None, []
        self.scanning = True
        self.started = time.perf_counter()
        self.btn.config(text="Stop")
        self.status.config(text=f"Mencari ({mode}) ...")
        self.scan_future = asyncio.run_coroutine_threadsafe(
            self._scan(mode_core, target, self.live.get()), self.loop)

    def stop(self, msg=None):
        self.scanning = False
        if self.stop_event is not None:
            self.loop.call_soon_threadsafe(self.stop_event.set)
        self.btn.config(text="Scan")
        if msg:
            self.status.config(text=msg)

    async def _scan(self, mode, target, live):
        self.stop_event = asyncio.Event()
        found = False

        def on_adv(device, adv):
            nonlocal found
            beacon = core.parse_ibeacon(adv.manufacturer_data)
            if not beacon:
                return
            name = adv.local_name or device.name
            if not core.matches(mode, target, name, device.address, beacon):
                return
            found = True
            now = time.perf_counter()
            td = (adv.service_data or {}).get(TYPE_DATA_UUID)
            stype = td[0] if td else 0
            self.ui(self._on_found, name, device.address,
                    beacon, adv.rssi, now, stype)
            if not live:
                self.stop_event.set()

        try:
            async with BleakScanner(detection_callback=on_adv, scanning_mode="active"):
                try:
                    timeout = None if live else SCAN_TIMEOUT_S
                    await asyncio.wait_for(self.stop_event.wait(), timeout)
                except asyncio.TimeoutError:
                    pass
        except Exception as e:  # Bluetooth mati / tidak ada adapter
            self.ui(self.stop, f"Gagal scan: {e}")
            return
        if not found:
            self.ui(self.stop,
                            "Device tidak ditemukan. Pastikan ESP32 menyala & Bluetooth laptop aktif.")

    def _on_found(self, name, address, b, rssi, t, stype=0):
        self.show_type(stype)
        self.hits += 1
        if self.last_hit is not None:
            self.intervals.append(t - self.last_hit)
            self.intervals = self.intervals[-20:]
        first_ms = (t - self.started) * 1000 if self.hits == 1 else None
        self.last_hit = t
        if self.hits == 1:
            self.first_ms = first_ms

        kg = core.weight_kg(b["major"], b["minor"])
        self.lbl_major.config(text=f"Major : {b['major']} = hex ({b['major']:x})")
        self.lbl_minor.config(text=f"Minor : {b['minor']} = hex ({b['minor']:x})")
        self.lbl_weight.config(text=f"Beratnya = {core.format_kg(kg)} KG")
        self.set_info(f"Nama : {name or '-'}\nUUID : {b['uuid']}\n"
                      f"MAC  : {address}\nRSSI : {rssi} dBm")

        speed = f"Data pertama: {self.first_ms:.0f} ms"
        if self.intervals:
            avg = sum(self.intervals) / len(self.intervals) * 1000
            speed += f" | Update ke-{self.hits}, rata-rata tiap {avg:.0f} ms"
        if self.live.get() and self.scanning:
            self.status.config(text="Live... " + speed)
        else:
            self.stop("Ditemukan. " + speed)


    # ---------- Jenis timbangan -> ESP32 ----------
    def show_type(self, stype):
        if stype in TYPE_NAMES:
            self.lbl_type.config(text=f"Jenis aktif di ESP32: {TYPE_NAMES[stype]}")
            self.scale_type.set(stype)
        else:
            self.lbl_type.config(text="Jenis aktif di ESP32: tidak diketahui (firmware ESP32 lama?)")

    def apply_type(self):
        if self.scanning or self.applying:
            return
        mode = self.mode.get()
        mode_core = "NAME" if mode == "NAMA" else mode
        target, err = core.normalize_target(mode_core, self.entry.get())
        if err:
            messagebox.showwarning("Input", err)
            return
        stype = self.scale_type.get()
        self.applying = True
        self.btn_apply.config(state="disabled")
        self.btn.config(state="disabled")
        self.status.config(text="Mencari ESP32 untuk mengirim jenis timbangan...")
        asyncio.run_coroutine_threadsafe(self._apply(mode_core, target, stype), self.loop)

    def _apply_done(self, msg, stype=None):
        self.applying = False
        self.btn_apply.config(state="normal")
        self.btn.config(state="normal")
        if stype:
            self.show_type(stype)
        self.status.config(text=msg)

    async def _apply(self, mode, target, stype):
        def match(device, adv):
            beacon = core.parse_ibeacon(adv.manufacturer_data)
            name = adv.local_name or device.name
            return bool(beacon) and core.matches(mode, target, name, device.address, beacon)

        try:
            device = await BleakScanner.find_device_by_filter(match, timeout=SCAN_TIMEOUT_S)
            if device is None:
                self.ui(self._apply_done, "Device tidak ditemukan. Pastikan ESP32 menyala.")
                return
            self.ui(self.status.config, {"text": "Menghubungkan ke ESP32..."})
            last_err = None
            for _ in range(2):                       # coba 2x, koneksi pertama kadang gagal
                try:
                    async with BleakClient(device, timeout=15) as client:
                        await client.write_gatt_char(CFG_TYPE_UUID, bytes([stype]), response=True)
                    self.ui(self._apply_done,
                            f"Jenis timbangan di ESP32 diubah ke: {TYPE_NAMES[stype]}", stype)
                    return
                except Exception as e:  # noqa: BLE001
                    last_err = e
                    await asyncio.sleep(1)
            msg = str(last_err)
            if "not found" in msg.lower() or "characteristic" in msg.lower():
                msg = "ESP32 belum mendukung pilihan jenis timbangan. Upload sketch ESP32 terbaru."
            self.ui(self._apply_done, f"Gagal: {msg}")
        except Exception as e:  # noqa: BLE001
            self.ui(self._apply_done, f"Gagal: {e}")


def selftest():
    """Dipakai CI: pastikan modul Bluetooth Windows ikut ter-bundle di exe."""
    import sys
    import traceback
    try:
        from bleak.backends.winrt.scanner import BleakScannerWinRT  # noqa: F401
        from bleak.backends.winrt.client import BleakClientWinRT  # noqa: F401
        b = core.parse_ibeacon({0x4C: bytes([2, 0x15]) + bytes(16) + bytes([0, 3, 0, 62, 0xC5])})
        assert core.format_kg(core.weight_kg(b["major"], b["minor"])) == "83"
        sys.exit(0)
    except Exception:
        with open("selftest_error.txt", "w") as fh:
            fh.write(traceback.format_exc())
        sys.exit(1)


def main():
    import sys
    if "--selftest" in sys.argv:
        selftest()
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
