"""Unit tests for J2208A BLE protocol framing, sleep parsing, and post-12:00 noon sleep stitcher."""

import unittest
from tools.ble_verify.j2208_verify import (
    analyze_post_1200_noon_sleep,
    bcd,
    frame,
    parse_records,
    parse_sleep_packet,
    record_datetime,
    unbcd,
)


class TestJ2208Protocol(unittest.TestCase):
    def test_bcd_and_unbcd(self):
        self.assertEqual(bcd(26), 0x26)
        self.assertEqual(bcd(59), 0x59)
        self.assertEqual(unbcd(0x26), 26)
        self.assertEqual(unbcd(0x59), 59)

    def test_16_byte_frame_checksum(self):
        pkt = frame(0x2A, 2, 0x00, 0x00, 0x23, 0x59, 0xFF, 10, 0, 4)
        self.assertEqual(len(pkt), 16)
        self.assertEqual(pkt[0], 0x2A)
        self.assertEqual(pkt[15], sum(pkt[:15]) & 0xFF)

    def test_parse_130_byte_sleep_block(self):
        buf = bytearray(130)
        buf[0] = 0x53
        # 2026-11-12 04:30:00 in BCD at +3..+8
        buf[3:9] = bytes([0x26, 0x11, 0x12, 0x04, 0x30, 0x00])
        buf[9] = 4  # 4 minutes
        buf[10:14] = bytes([2, 1, 3, 5])  # light, deep, rem, awake
        parsed = parse_sleep_packet(bytes(buf))
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["device_time"], "2026-11-12T04:30:00")
        self.assertEqual(parsed[0]["light_min"], 1)
        self.assertEqual(parsed[0]["deep_min"], 1)
        self.assertEqual(parsed[0]["rem_min"], 1)
        self.assertEqual(parsed[0]["awake_min"], 1)

    def test_post_1200_noon_sleep_stitcher_with_step_and_hr_fallback(self):
        # Sleep block from 10:00 to 12:00 (120 mins of light/deep sleep ending right at 12:00 noon)
        sleep_rows = [{
            "device_time": "2026-11-12T10:00:00",
            "unit_minutes": 1,
            "count": 120,
            "stages": [2] * 60 + [1] * 60,
        }]
        # Step logs after 12:00 noon showing 0 steps until 13:30
        step_rows = [
            {"device_time": "2026-11-12T12:10:00", "steps": 0},
            {"device_time": "2026-11-12T12:20:00", "steps": 0},
            {"device_time": "2026-11-12T12:30:00", "steps": 0},
            {"device_time": "2026-11-12T13:30:00", "steps": 145},
        ]
        hr_rows = [
            {"device_time": "2026-11-12T12:10:00", "value": 54},
            {"device_time": "2026-11-12T12:25:00", "value": 56},
            {"device_time": "2026-11-12T13:30:00", "value": 88},
        ]
        analysis = analyze_post_1200_noon_sleep(sleep_rows, step_rows, hr_rows)
        self.assertEqual(analysis["inferred_post_1200_noon_sleep_minutes_from_steps_and_hr"], 30)


if __name__ == "__main__":
    unittest.main()
