"""Phase 1 Pre-Sunday Verification & History Dump Tool for J2208A (J2208A2 17F6).

Connects directly over Windows BLE (no encryption/auth required) to:
1. Scan for J2208A / 0xFFF0 service devices (or connect by address/name).
2. Read Firmware Version (0x27), Battery (0x13), MAC (0x22), and Device RTC Time (0x41).
3. Read and configure Automatic 24/7 Background Monitoring (0x2B / 0x2A) for HR, SpO2, Temp, and HRV.
4. Download all stored history into SQLite (tools/ble_verify/dump.db) + JSON report:
   - Daily Totals (0x51)
   - Detailed Steps & 1-min sub-intervals (0x52)
   - Sleep Stages (0x53 - both 130-byte 1-min blocks and 34-byte 5-min blocks)
   - Heart Rate (0x55)
   - HRV, Stress & Blood Pressure (0x56)
   - Temperature (0x65 & 0x62)
   - SpO2 (0x66 & 0x60)
5. Analyze the 12:00 (12:00 PM noon military time) Sleep Cutoff:
   - Checks if raw 0x53 sleep epochs exist after 12:00 noon.
   - Demonstrates post-12:00 noon sleep continuation reconstruction using 0x52 step inactivity + 0x55/0x56 sleeping HR/HRV.
6. Run a Live Sensor Stream test (0x28) for live HR, HRV, SpO2, Stress, and Skin Temp.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sqlite3
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
CHAR_WRITE = "0000fff6-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY = "0000fff7-0000-1000-8000-00805f9b34fb"

OP_SET_TIME = 0x01
OP_GET_BATTERY = 0x13
OP_GET_MAC = 0x22
OP_GET_VERSION = 0x27
OP_MEASUREMENT = 0x28
OP_SET_AUTO = 0x2A
OP_GET_AUTO = 0x2B
OP_GET_TIME = 0x41

AUTO_METRICS = {"hr": 1, "spo2": 2, "temp": 3, "hrv": 4}
AUTO_METRIC_NAMES = {v: k for k, v in AUTO_METRICS.items()}

SLEEP_STAGES = {1: "deep", 2: "light", 3: "rem", 5: "awake"}

HISTORY_SPECS = {
    "totals":    {"op": 0x51, "stride": 26, "fields": "totals", "date_only": True},
    "steps":     {"op": 0x52, "stride": 25, "fields": "steps"},
    "sleep":     {"op": 0x53, "stride": 34, "fields": "sleep"},
    "hr":        {"op": 0x55, "stride": 10, "fields": "u8"},
    "hrv":       {"op": 0x56, "stride": 15, "fields": "hrv"},
    "temp_axil": {"op": 0x65, "stride": 11, "fields": "u16x0.1"},
    "temp":      {"op": 0x62, "stride": 11, "fields": "u16x0.1"},
    "spo2_auto": {"op": 0x66, "stride": 10, "fields": "u8"},
    "spo2":      {"op": 0x60, "stride": 10, "fields": "u8"},
}


def bcd(val: int) -> int:
    return int(f"{val:02d}", 16)


def unbcd(byte: int) -> int:
    return ((byte >> 4) & 0x0F) * 10 + (byte & 0x0F)


def u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def u32le(data: bytes, offset: int) -> int:
    return int.from_bytes(data[offset : offset + 4], "little")


def frame(opcode: int, *args: int) -> bytes:
    pkt = bytearray(16)
    pkt[0] = opcode & 0xFF
    for i, v in enumerate(args, start=1):
        if i < 15:
            pkt[i] = v & 0xFF
    pkt[15] = sum(pkt[:15]) & 0xFF
    return bytes(pkt)


def record_datetime(rec: bytes, date_only: bool = False) -> str | None:
    if date_only:
        if len(rec) < 5:
            return None
        yy, mm, dd = (unbcd(b) for b in rec[2:5])
        hh = mi = ss = 0
    else:
        if len(rec) < 9:
            return None
        yy, mm, dd, hh, mi, ss = (unbcd(b) for b in rec[3:9])
    if not (1 <= mm <= 12 and 1 <= dd <= 31 and 0 <= hh < 24 and 0 <= mi < 60 and 0 <= ss < 60):
        return None
    if date_only:
        return f"20{yy:02d}-{mm:02d}-{dd:02d}"
    return f"20{yy:02d}-{mm:02d}-{dd:02d}T{hh:02d}:{mi:02d}:{ss:02d}"


def parse_sleep_packet(data: bytes) -> list[dict]:
    """Handles both 130-byte (1-min epoch) and stride-34 (5-min epoch) sleep records."""
    out = []
    if len(data) == 130 and len(data) >= 10:
        dt = record_datetime(data)
        count = min(data[9], len(data) - 10)
        stages = list(data[10 : 10 + count])
        mins = {name: 0 for name in ("deep", "light", "rem", "awake")}
        for s in stages:
            label = SLEEP_STAGES.get(s)
            if label:
                mins[label] += 1
        out.append({
            "device_time": dt,
            "unit_minutes": 1,
            "count": count,
            "deep_min": mins["deep"],
            "light_min": mins["light"],
            "rem_min": mins["rem"],
            "awake_min": mins["awake"],
            "stages": stages,
            "raw": data.hex(),
        })
        return out

    # Fallback: stride 34 (5-minute epoch blocks used on some J-Style firmwares)
    stride = 34
    for i in range(len(data) // stride):
        rec = data[i * stride : (i + 1) * stride]
        dt = record_datetime(rec)
        count = min(rec[9], 24)
        stages = list(rec[10 : 10 + count])
        mins = {name: 0 for name in ("deep", "light", "rem", "awake")}
        for s in stages:
            label = SLEEP_STAGES.get(s)
            if label:
                mins[label] += 5
        out.append({
            "device_time": dt,
            "unit_minutes": 5,
            "count": count,
            "deep_min": mins["deep"],
            "light_min": mins["light"],
            "rem_min": mins["rem"],
            "awake_min": mins["awake"],
            "stages": stages,
            "raw": rec.hex(),
        })
    return out


def totals_stride(n: int) -> int:
    if n != 2:
        if n % 26 == 0:
            return 26
        if n % 27 == 0:
            return 27
        if (n - 2) % 26 == 0:
            return 26
    return 27


def parse_records(data: bytes, stride: int, fields: str | None, date_only: bool = False) -> list[dict]:
    if stride <= 0:
        return []
    out = []
    for i in range(len(data) // stride):
        rec = data[i * stride : (i + 1) * stride]
        row: dict = {"device_time": record_datetime(rec, date_only), "raw": rec.hex()}
        if fields == "totals" and len(rec) >= 26:
            row["steps"] = u32le(rec, 5)
            row["exercise_sec"] = u32le(rec, 9)
            row["distance_km"] = round(u32le(rec, 13) / 100.0, 2)
            row["calories_kcal"] = round(u32le(rec, 17) / 100.0, 2)
            row["goal"] = rec[21] if stride == 26 else u16le(rec, 21)
            row["active_minutes"] = u32le(rec, stride - 4)
            out.append(row)
            continue
        if fields == "steps" and len(rec) >= 25:
            row["steps"] = u16le(rec, 9)
            row["calories_kcal"] = round(u16le(rec, 11) / 100.0, 2)
            row["distance_km"] = round(u16le(rec, 13) / 100.0, 2)
            row["step_series"] = list(rec[15:25])
            out.append(row)
            continue
        if len(rec) < 10:
            out.append(row)
            continue
        if fields == "u8":
            row["value"] = rec[9]
        elif fields == "u16x0.1" and len(rec) >= 11:
            row["value"] = round(u16le(rec, 9) * 0.1, 1)
        elif fields == "hrv" and len(rec) >= 15:
            row["hrv"] = rec[9]
            row["vascular_aging"] = rec[10]
            row["hr"] = rec[11]
            row["stress"] = rec[12]
            row["bp_sys"] = rec[13]
            row["bp_dia"] = rec[14]
        out.append(row)
    return out


def init_sqlite(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS sleep_blocks (
            device_time TEXT PRIMARY KEY,
            unit_minutes INTEGER,
            count INTEGER,
            deep_min INTEGER,
            light_min INTEGER,
            rem_min INTEGER,
            awake_min INTEGER,
            stages_json TEXT,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS hrv_logs (
            device_time TEXT PRIMARY KEY,
            hrv INTEGER,
            hr INTEGER,
            stress INTEGER,
            vascular_aging INTEGER,
            bp_sys INTEGER,
            bp_dia INTEGER,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS hr_logs (
            device_time TEXT PRIMARY KEY,
            bpm INTEGER,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS spo2_logs (
            device_time TEXT PRIMARY KEY,
            spo2_pct INTEGER,
            source TEXT,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS temp_logs (
            device_time TEXT PRIMARY KEY,
            temp_c REAL,
            source TEXT,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS step_logs (
            device_time TEXT PRIMARY KEY,
            steps INTEGER,
            calories_kcal REAL,
            distance_km REAL,
            step_series_json TEXT,
            raw TEXT
        );
        CREATE TABLE IF NOT EXISTS daily_totals (
            device_date TEXT PRIMARY KEY,
            steps INTEGER,
            exercise_sec INTEGER,
            distance_km REAL,
            calories_kcal REAL,
            goal INTEGER,
            active_minutes INTEGER,
            raw TEXT
        );
        """
    )
    conn.commit()
    return conn


async def find_j2208_device(scan_seconds: float = 8.0, target_name: str = "J2208") -> tuple[str | None, str | None, list[dict]]:
    print(f"[*] Scanning BLE for {scan_seconds:.1f}s (looking for '{target_name}' or service 0xFFF0)...")
    devices = await BleakScanner.discover(timeout=scan_seconds, return_adv=True)
    all_devs = []
    chosen_addr = None
    chosen_name = None

    for dev, adv in devices.values():
        name = adv.local_name or dev.name or ""
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        is_j2208 = (
            SERVICE_UUID in uuids
            or target_name.lower() in name.lower()
            or "2208" in name.lower()
            or "hume" in name.lower()
        )
        all_devs.append({
            "address": dev.address,
            "name": name or "(unnamed)",
            "rssi": adv.rssi,
            "is_j2208": is_j2208,
            "uuids": uuids,
        })
        if is_j2208 and chosen_addr is None:
            chosen_addr = dev.address
            chosen_name = name

    all_devs.sort(key=lambda d: (not d["is_j2208"], -(d["rssi"] or -999)))
    for d in all_devs[:12]:
        tag = "  <== MATCH (J2208 / 0xFFF0)" if d["is_j2208"] else ""
        print(f"    RSSI {str(d['rssi']):>4} | {d['address']:<18} | {d['name']:<22}{tag}")

    return chosen_addr, chosen_name, all_devs


async def download_history_type(client: BleakClient, write_char, htype: str, timeout_s: float = 45.0, idle_s: float = 1.2) -> list[dict]:
    spec = HISTORY_SPECS[htype]
    op = spec["op"]
    stride = spec["stride"]
    fields = spec["fields"]
    date_only = spec.get("date_only", False)

    done = asyncio.Event()
    packets: list[bytes] = []
    state = {"count": 0, "last_rx": time.monotonic(), "continues": 0}

    no_resp = "write-without-response" in write_char.properties

    async def send_pkt(pkt: bytes):
        await client.write_gatt_char(write_char, pkt, response=not no_resp)

    async def ask_continue():
        state["continues"] += 1
        state["last_rx"] = time.monotonic()
        await send_pkt(frame(op, 2))

    def on_notify(_char, data: bytearray):
        raw = bytes(data)
        if not raw or raw[0] != op:
            return
        state["last_rx"] = time.monotonic()
        if raw[-2:] == bytes([op, 0xFF]):
            done.set()
            if len(raw) == 2:
                return
            raw = raw[:-2]
        packets.append(raw)
        state["count"] += 1
        if state["count"] % 50 == 0:
            asyncio.get_running_loop().create_task(ask_continue())

    await client.start_notify(CHAR_NOTIFY, on_notify)
    try:
        await send_pkt(frame(op, 0))
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            try:
                await asyncio.wait_for(done.wait(), timeout=0.25)
                break
            except asyncio.TimeoutError:
                pass
            if time.monotonic() - state["last_rx"] > idle_s:
                await ask_continue()
    finally:
        await client.stop_notify(CHAR_NOTIFY)

    rows: list[dict] = []
    for pkt in packets:
        if fields == "sleep":
            rows.extend(parse_sleep_packet(pkt))
        else:
            st = totals_stride(len(pkt)) if fields == "totals" else stride
            rows.extend(parse_records(pkt, st, fields, date_only))
    return [r for r in rows if r.get("device_time")]


def analyze_post_1200_noon_sleep(sleep_rows: list[dict], step_rows: list[dict], hr_rows: list[dict]) -> dict:
    """Analyzes sleep around the 12:00 (12:00 PM noon military time) boundary."""
    expanded_epochs: list[tuple[datetime, str]] = []
    for block in sorted(sleep_rows, key=lambda r: r["device_time"]):
        start_dt = datetime.fromisoformat(block["device_time"])
        unit = block.get("unit_minutes", 1)
        for idx, code in enumerate(block.get("stages", [])):
            stage_name = SLEEP_STAGES.get(code, "unknown")
            epoch_dt = start_dt + timedelta(minutes=idx * unit)
            expanded_epochs.append((epoch_dt, stage_name))

    post_noon_raw_epochs = [
        (dt.isoformat(), st)
        for dt, st in expanded_epochs
        if 12 <= dt.hour < 18
    ]
    near_noon_Cutoffs = [
        dt.isoformat()
        for dt, _ in expanded_epochs
        if dt.hour == 11 and dt.minute >= 45
    ]

    # Check if there are zero-step + low-HR periods between 12:00 and 16:00 that extend sleep
    hr_by_time = {
        datetime.fromisoformat(r["device_time"]): r["value"]
        for r in hr_rows
        if r.get("value", 0) > 0
    }
    inferred_post_noon_minutes = 0
    for srec in step_rows:
        dt = datetime.fromisoformat(srec["device_time"])
        if 12 <= dt.hour < 16 and srec.get("steps", 0) == 0:
            # Check if HR around this time is in sleeping/resting range (< 70 bpm)
            nearby_hrs = [
                bpm for hdt, bpm in hr_by_time.items()
                if abs((hdt - dt).total_seconds()) <= 900
            ]
            if nearby_hrs and (sum(nearby_hrs) / len(nearby_hrs)) < 68:
                inferred_post_noon_minutes += 10

    return {
        "total_sleep_blocks": len(sleep_rows),
        "total_expanded_sleep_epochs": len(expanded_epochs),
        "raw_post_1200_noon_epochs_count": len(post_noon_raw_epochs),
        "near_1200_noon_epochs_sample": near_noon_Cutoffs[-5:],
        "inferred_post_1200_noon_sleep_minutes_from_steps_and_hr": inferred_post_noon_minutes,
        "conclusion": (
            "Raw 0x53 sleep epochs DO appear after 12:00 noon — the 12:00 cutoff is purely in the JCWear Pro app UI!"
            if post_noon_raw_epochs
            else "Firmware 0x53 sleep window ends at 12:00 noon, so our Android app's Post-12:00 Stitcher will automatically extend sleep past 12:00 using 0x52 (0-step sub-epochs) + 0x55/0x56 (sleeping HR/HRV)!"
        ),
    }


async def run_full_verification(address: str, set_interval: int | None, db_path: Path, live_seconds: int = 15):
    conn = init_sqlite(db_path)
    report: dict = {"address": address, "timestamp": datetime.now().isoformat(), "device_info": {}, "auto_schedule": {}, "counts": {}}

    async with BleakClient(address, timeout=25.0) as client:
        print(f"[+] Connected to {address}")
        svc = client.services.get_service(SERVICE_UUID)
        if svc is None:
            raise RuntimeError(f"Service {SERVICE_UUID} not found on {address}")
        write_char = svc.get_characteristic(CHAR_WRITE)
        no_resp = "write-without-response" in write_char.properties

        info_state: dict = {}

        def on_info_notify(_char, data: bytearray):
            raw = bytes(data)
            if not raw:
                return
            op = raw[0]
            if op == OP_GET_BATTERY and len(raw) >= 2:
                info_state["battery_pct"] = raw[1]
            elif op == OP_GET_VERSION and len(raw) >= 8:
                ver = ".".join(str(b) for b in raw[1:5])
                build = f"20{raw[5]:02x}-{raw[6]:02x}-{raw[7]:02x}"
                info_state["firmware_version"] = ver
                info_state["firmware_build_date"] = build
            elif op == OP_GET_TIME and len(raw) >= 7:
                info_state["device_rtc"] = (
                    f"20{unbcd(raw[1]):02d}-{unbcd(raw[2]):02d}-{unbcd(raw[3]):02d}"
                    f"T{unbcd(raw[4]):02d}:{unbcd(raw[5]):02d}:{unbcd(raw[6]):02d}"
                )
            elif op == OP_GET_AUTO and len(raw) >= 10:
                mname = AUTO_METRIC_NAMES.get(raw[9], f"0x{raw[9]:02x}")
                info_state.setdefault("auto", {})[mname] = {
                    "enabled": raw[1] == 2,
                    "window": f"{unbcd(raw[2]):02d}:{unbcd(raw[3]):02d}-{unbcd(raw[4]):02d}:{unbcd(raw[5]):02d}",
                    "interval_min": u16le(raw, 7),
                }

        await client.start_notify(CHAR_NOTIFY, on_info_notify)
        for op in (OP_GET_VERSION, OP_GET_BATTERY, OP_GET_TIME):
            await client.write_gatt_char(write_char, frame(op), response=not no_resp)
            await asyncio.sleep(0.4)

        if set_interval is not None:
            print(f"[*] Configuring 24/7 automatic sampling (00:00-23:59) to {set_interval} min for HR, HRV, SpO2, Temp...")
            for mname, mcode in AUTO_METRICS.items():
                pkt = frame(
                    OP_SET_AUTO,
                    2,
                    0x00, 0x00,  # 00:00
                    0x23, 0x59,  # 23:59
                    0xFF,        # all 7 days
                    set_interval & 0xFF, (set_interval >> 8) & 0xFF,
                    mcode,
                )
                await client.write_gatt_char(write_char, pkt, response=not no_resp)
                await asyncio.sleep(0.5)

        for mname, mcode in AUTO_METRICS.items():
            await client.write_gatt_char(write_char, frame(OP_GET_AUTO, mcode), response=not no_resp)
            await asyncio.sleep(0.4)
        await asyncio.sleep(0.8)
        await client.stop_notify(CHAR_NOTIFY)

        report["device_info"] = {k: v for k, v in info_state.items() if k != "auto"}
        report["auto_schedule"] = info_state.get("auto", {})
        print(f"[+] Device Info: {json.dumps(report['device_info'])}")
        print(f"[+] 24/7 Auto-Sampling Schedule: {json.dumps(report['auto_schedule'])}")

        # Download all stored histories
        downloaded: dict[str, list[dict]] = {}
        for htype in ("totals", "sleep", "hrv", "hr", "temp_axil", "spo2_auto", "steps"):
            print(f"[*] Downloading '{htype}' (0x{HISTORY_SPECS[htype]['op']:02X})...")
            rows = await download_history_type(client, write_char, htype)
            downloaded[htype] = rows
            report["counts"][htype] = len(rows)
            print(f"    -> {len(rows)} records")

        # Save to SQLite
        for r in downloaded.get("sleep", []):
            conn.execute(
                "INSERT OR REPLACE INTO sleep_blocks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (r["device_time"], r["unit_minutes"], r["count"], r["deep_min"], r["light_min"], r["rem_min"], r["awake_min"], json.dumps(r["stages"]), r["raw"]),
            )
        for r in downloaded.get("hrv", []):
            conn.execute(
                "INSERT OR REPLACE INTO hrv_logs VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (r["device_time"], r.get("hrv"), r.get("hr"), r.get("stress"), r.get("vascular_aging"), r.get("bp_sys"), r.get("bp_dia"), r["raw"]),
            )
        for r in downloaded.get("hr", []):
            conn.execute(
                "INSERT OR REPLACE INTO hr_logs VALUES (?, ?, ?)",
                (r["device_time"], r.get("value"), r["raw"]),
            )
        for r in downloaded.get("spo2_auto", []):
            conn.execute(
                "INSERT OR REPLACE INTO spo2_logs VALUES (?, ?, ?, ?)",
                (r["device_time"], r.get("value"), "auto_0x66", r["raw"]),
            )
        for r in downloaded.get("temp_axil", []):
            conn.execute(
                "INSERT OR REPLACE INTO temp_logs VALUES (?, ?, ?, ?)",
                (r["device_time"], r.get("value"), "0x65", r["raw"]),
            )
        for r in downloaded.get("steps", []):
            conn.execute(
                "INSERT OR REPLACE INTO step_logs VALUES (?, ?, ?, ?, ?, ?)",
                (r["device_time"], r.get("steps"), r.get("calories_kcal"), r.get("distance_km"), json.dumps(r.get("step_series", [])), r["raw"]),
            )
        for r in downloaded.get("totals", []):
            conn.execute(
                "INSERT OR REPLACE INTO daily_totals VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (r["device_time"], r.get("steps"), r.get("exercise_sec"), r.get("distance_km"), r.get("calories_kcal"), r.get("goal"), r.get("active_minutes"), r["raw"]),
            )
        conn.commit()

        # Analyze 12:00 (noon military time) sleep behavior
        report["sleep_1200_noon_analysis"] = analyze_post_1200_noon_sleep(
            downloaded.get("sleep", []),
            downloaded.get("steps", []),
            downloaded.get("hr", []),
        )

        # Live sensor test (0x28)
        if live_seconds > 0:
            print(f"[*] Testing live sensor stream (0x28) for {live_seconds}s...")
            live_samples = []

            def on_live(_char, data: bytearray):
                raw = bytes(data)
                if len(raw) >= 10 and raw[0] == OP_MEASUREMENT:
                    live_samples.append({
                        "hr": raw[2],
                        "spo2": raw[3],
                        "hrv": raw[4],
                        "stress": raw[5],
                        "temp_c": round(u16le(raw, 8) * 0.1, 1),
                    })

            await client.start_notify(CHAR_NOTIFY, on_live)
            await client.write_gatt_char(write_char, frame(OP_MEASUREMENT, 2, 1), response=not no_resp)
            await asyncio.sleep(live_seconds)
            await client.write_gatt_char(write_char, frame(OP_MEASUREMENT, 2, 0), response=not no_resp)
            await client.stop_notify(CHAR_NOTIFY)
            report["live_stream_samples_count"] = len(live_samples)
            report["live_stream_sample_preview"] = live_samples[-5:]

    report_path = db_path.with_suffix(".report.json")
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\n[+] Verification complete! SQLite DB: {db_path} | Report: {report_path}")
    return report


async def async_main() -> int:
    parser = argparse.ArgumentParser(description="J2208A Pre-Sunday BLE Verification & History Dump Tool")
    parser.add_argument("--scan-only", action="store_true", help="Only scan for BLE devices and exit")
    parser.add_argument("--address", type=str, default=None, help="BLE MAC address of J2208A (auto-discovered if omitted)")
    parser.add_argument("--set-interval", type=int, default=10, help="Configure 24/7 auto-sampling interval in minutes (default: 10)")
    parser.add_argument("--live-seconds", type=int, default=10, help="Seconds to test live 0x28 stream")
    parser.add_argument("--db", type=str, default="tools/ble_verify/dump.db", help="Output SQLite database path")
    args = parser.parse_args()

    addr = args.address
    if not addr or args.scan_only:
        found_addr, found_name, _ = await find_j2208_device(scan_seconds=8.0)
        if args.scan_only:
            return 0 if found_addr else 1
        addr = found_addr
        if not addr:
            print("\n[!] J2208A2 17F6 was not seen advertising during scan.")
            print("    -> Make sure the band is disconnected from the JCWear Pro app on your phone (or turn off phone Bluetooth briefly) and run again.")
            return 2

    await run_full_verification(
        address=addr,
        set_interval=args.set_interval,
        db_path=Path(args.db),
        live_seconds=args.live_seconds,
    )
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(async_main()))
