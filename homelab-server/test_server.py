"""Automated tests for the Homelab FastAPI Sync & Claude Coach service."""

import os
import sys
import unittest
from pathlib import Path

os.environ["SQLITE_FALLBACK_PATH"] = "./test_homelab_memory.db"
sys.path.insert(0, str(Path(__file__).parent))

from fastapi.testclient import TestClient
import app.main as main
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
        today = [d for d in data["daily_summaries"] if d["date"] == "2026-11-12"]
        self.assertEqual(today[0]["readiness_score"], 84)

        coach_res = self.client.post(
            "/api/v1/coach/chat",
            json={
                "messages": [{"role": "user", "content": "Should I do heavy intervals today?"}],
                "include_health_context": True,
            },
        )
        self.assertEqual(coach_res.status_code, 200)
        if coach_res.json()["model"] == "offline-fallback":
            self.assertIn("Recovery is 84", coach_res.json()["reply"])

    def _secured(self, token="t0ken"):
        main.SYNC_API_TOKEN = token
        self.addCleanup(setattr, main, "SYNC_API_TOKEN", "")
        return {"X-Api-Token": token}

    def test_null_summary_fields_stay_null(self):
        res = self.client.post("/api/v1/sync", json={"daily_summaries": [{"date": "2026-10-01", "resting_hr_bpm": 51.0}]})
        self.assertEqual(res.status_code, 200)
        with main.SessionLocal() as db:
            row = db.get(main.DailyReadinessSummaryRow, "2026-10-01")
            self.assertIsNone(row.spo2_avg_pct)
            self.assertIsNone(row.readiness_score)
            self.assertEqual(row.resting_hr_bpm, 51.0)

    def test_restore_all_pages_and_never_returns_seed(self):
        hr = [{"device_time": f"2026-09-01T00:0{i}:00", "bpm": 50 + i, "raw": "seed" if i == 1 else "r"} for i in range(5)]
        self.client.post("/api/v1/sync", json={"hr_logs": hr})
        page1 = self.client.get("/api/v1/restore", params={"all": True, "table": "hr_logs", "limit": 2}).json()
        self.assertEqual([r["device_time"][-5:] for r in page1["rows"]], ["00:00", "02:00"])
        page2 = self.client.get("/api/v1/restore", params={"all": True, "table": "hr_logs", "limit": 2, "after": page1["next_after"]}).json()
        times = [r["device_time"] for r in page2["rows"]]
        self.assertNotIn("2026-09-01T00:01:00", times)
        self.assertEqual(times[:2], ["2026-09-01T00:03:00", "2026-09-01T00:04:00"])
        self.assertEqual(self.client.get("/api/v1/restore", params={"all": True, "table": "bogus"}).status_code, 400)

    def test_destructive_endpoints_refuse_without_token_configured(self):
        self.assertEqual(self.client.post("/api/v1/purge-seeded").status_code, 503)
        self.assertEqual(self.client.delete("/api/v1/summaries", params={"dates": "2026-10-01"}).status_code, 503)

    def test_purge_seeded_deletes_only_seed_rows(self):
        headers = self._secured()
        self.client.post("/api/v1/sync", headers=headers, json={"spo2_logs": [
            {"device_time": "2026-08-01T03:00:00", "spo2_pct": 97, "raw": "seed"},
            {"device_time": "2026-08-01T04:00:00", "spo2_pct": 96, "raw": "real"},
        ]})
        self.assertEqual(self.client.post("/api/v1/purge-seeded").status_code, 401)
        res = self.client.post("/api/v1/purge-seeded", headers=headers)
        self.assertEqual(res.status_code, 200)
        with main.SessionLocal() as db:
            raws = [r.raw for r in db.query(main.Spo2LogRow).filter(main.Spo2LogRow.device_time.like("2026-08-01%"))]
        self.assertEqual(raws, ["real"])

    def test_delete_summaries(self):
        headers = self._secured()
        self.client.post("/api/v1/sync", headers=headers, json={"daily_summaries": [{"date": "2026-07-01"}, {"date": "2026-07-02"}]})
        res = self.client.delete("/api/v1/summaries", headers=headers, params={"dates": "2026-07-01"})
        self.assertEqual(res.json()["deleted"], 1)
        with main.SessionLocal() as db:
            self.assertIsNotNone(db.get(main.DailyReadinessSummaryRow, "2026-07-02"))


if __name__ == "__main__":
    unittest.main()
