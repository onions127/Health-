"""Automated tests for the Homelab FastAPI Sync & Claude Coach service."""

import os
import sys
import unittest
from pathlib import Path

os.environ["SQLITE_FALLBACK_PATH"] = "./test_homelab_memory.db"
sys.path.insert(0, str(Path(__file__).parent))

from fastapi.testclient import TestClient
from app.main import app


class TestHomelabServer(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_health_endpoint(self):
        res = self.client.get("/health")
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.json()["status"], "ok")

    def test_sync_and_restore_idempotent(self):
        payload = {
            "sleep_blocks": [
                {
                    "device_time": "2026-11-12T03:00:00",
                    "unit_minutes": 1,
                    "count": 120,
                    "deep_min": 35,
                    "light_min": 65,
                    "rem_min": 20,
                    "awake_min": 0,
                    "post_noon_extended_min": 45,
                    "stages": [2, 1, 3],
                    "raw": "5300",
                }
            ],
            "hrv_logs": [
                {
                    "device_time": "2026-11-12T06:00:00",
                    "hrv": 68,
                    "hr": 52,
                    "stress": 24,
                    "vascular_aging": 2,
                    "bp_sys": 115,
                    "bp_dia": 74,
                    "raw": "5600",
                }
            ],
            "daily_summaries": [
                {
                    "date": "2026-11-12",
                    "readiness_score": 84,
                    "sleep_score": 88,
                    "strain_score": 11.4,
                    "overnight_hrv_ms": 68.0,
                    "morning_rmssd_ms": 71.2,
                    "resting_hr_bpm": 52.0,
                    "total_sleep_min": 495,
                    "deep_sleep_min": 95,
                    "rem_sleep_min": 110,
                    "light_sleep_min": 290,
                    "spo2_avg_pct": 97.5,
                    "skin_temp_delta_c": 0.1,
                    "steps": 8420,
                }
            ],
        }
        sync_res = self.client.post("/api/v1/sync", json=payload)
        self.assertEqual(sync_res.status_code, 200)
        self.assertEqual(sync_res.json()["upserted"]["daily_summaries"], 1)

        restore_res = self.client.get("/api/v1/restore")
        self.assertEqual(restore_res.status_code, 200)
        data = restore_res.json()
        self.assertEqual(len(data["daily_summaries"]), 1)
        self.assertEqual(data["daily_summaries"][0]["readiness_score"], 84)

        coach_res = self.client.post(
            "/api/v1/coach/chat",
            json={
                "messages": [{"role": "user", "content": "Should I do heavy intervals today?"}],
                "include_health_context": True,
            },
        )
        self.assertEqual(coach_res.status_code, 200)
        self.assertIn("Readiness Score: 84/100", coach_res.json()["reply"])


if __name__ == "__main__":
    unittest.main()
