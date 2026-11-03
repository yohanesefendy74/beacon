"""Logika iBeacon tanpa UI/Bluetooth, supaya bisa dites terpisah."""
import re
import uuid as uuidlib

APPLE_COMPANY_ID = 0x004C

MAC_RE = re.compile(r"^([0-9A-F]{2}:){5}[0-9A-F]{2}$")


def parse_ibeacon(manufacturer_data):
    """manufacturer_data: dict {company_id: bytes} (format Bleak).
    Hasil: dict uuid/major/minor/tx atau None jika bukan iBeacon."""
    m = manufacturer_data.get(APPLE_COMPANY_ID) if manufacturer_data else None
    # iBeacon: 02 15 | UUID(16) | Major(2) | Minor(2) | TxPower(1)
    if not m or len(m) < 23 or m[0] != 0x02 or m[1] != 0x15:
        return None
    return {
        "uuid": str(uuidlib.UUID(bytes=bytes(m[2:18]))).upper(),
        "major": (m[18] << 8) | m[19],
        "minor": (m[20] << 8) | m[21],
        "tx": m[22] - 256 if m[22] > 127 else m[22],
    }


def weight_kg(major, minor):
    """nilai = berat x 10 -> Major byte tinggi, Minor byte rendah. 3,62 -> 83.0"""
    return (major * 256 + minor) / 10.0


def format_kg(kg):
    return str(int(kg)) if kg == int(kg) else f"{kg:.1f}"


def normalize_target(mode, text):
    """Validasi input filter. Hasil (nilai_normal, pesan_error)."""
    v = text.strip()
    if mode == "UUID":
        try:
            return str(uuidlib.UUID(v)).upper(), None
        except ValueError:
            return None, "Format UUID salah"
    if mode == "MAC":
        v = v.upper().replace("-", ":")
        if not MAC_RE.match(v):
            return None, "Format MAC salah, contoh AA:BB:CC:DD:EE:FF"
        return v, None
    if not v:
        return None, "Isi nama device"
    return v, None


def matches(mode, target, name, address, beacon):
    if mode == "UUID":
        return beacon["uuid"] == target
    if mode == "MAC":
        return (address or "").upper() == target
    return name is not None and name.lower() == target.lower()
