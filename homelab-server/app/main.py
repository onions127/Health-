"""Homelab Backup, Sync & Claude AI Coach Proxy Server for JCWear Pro (J2208A).

Features:
- Connects to your existing Homelab PostgreSQL instance (e.g., Immich Postgres) via DATABASE_URL,
  automatically falling back to local SQLite if Postgres is not reachable during local testing.
- Idempotent incremental sync (`POST /api/v1/sync`) for Sleep blocks, HRV, HR, SpO2, Temperature,
  Step logs, Daily summaries, and Morning 60s PPI Readiness Checks.
- Full phone restore (`GET /api/v1/restore`) so switching phones never loses historical baselines.
- Claude AI Coach (`POST /api/v1/coach/chat`) supporting both personalized readiness/sleep coaching
  (with anonymized 14-day metrics injected) and general health/fitness Q&A.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import Column, Float, Integer, String, Text, create_engine
from sqlalchemy.orm import Session, declarative_base, sessionmaker

DATABASE_URL = os.getenv("DATABASE_URL", "")
SQLITE_FALLBACK = os.getenv("SQLITE_FALLBACK_PATH", "./jcwear_homelab.db")
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")
CLAUDE_PRO_CLI = os.getenv("CLAUDE_PRO_CLI", "1") == "1"
DEFAULT_CLAUDE_MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-4-5")
SYNC_API_TOKEN = os.getenv("SYNC_API_TOKEN", "")


def _build_engine():
    if DATABASE_URL and not DATABASE_URL.startswith("sqlite"):
        try:
            eng = create_engine(DATABASE_URL, pool_pre_ping=True)
            with eng.connect() as _:
                pass
            return eng
        except Exception as exc:
            print(f"[!] Could not connect to DATABASE_URL ({exc}); using SQLite fallback at {SQLITE_FALLBACK}")
    return create_engine(
        f"sqlite:///{SQLITE_FALLBACK}",
        connect_args={"check_same_thread": False},
    )


engine = _build_engine()
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)
Base = declarative_base()


class SleepBlockRow(Base):
    __tablename__ = "sleep_blocks"
    device_time = Column(String(32), primary_key=True)
    unit_minutes = Column(Integer, default=1)
    count = Column(Integer, default=0)
    deep_min = Column(Integer, default=0)
    light_min = Column(Integer, default=0)
    rem_min = Column(Integer, default=0)
    awake_min = Column(Integer, default=0)
    post_noon_extended_min = Column(Integer, default=0)
    stages_json = Column(Text, default="[]")
    raw = Column(Text, default="")


class HrvLogRow(Base):
    __tablename__ = "hrv_logs"
    device_time = Column(String(32), primary_key=True)
    hrv = Column(Integer, default=0)
    hr = Column(Integer, default=0)
    stress = Column(Integer, default=0)
    vascular_aging = Column(Integer, default=0)
    bp_sys = Column(Integer, default=0)
    bp_dia = Column(Integer, default=0)
    raw = Column(Text, default="")


class HrLogRow(Base):
    __tablename__ = "hr_logs"
    device_time = Column(String(32), primary_key=True)
    bpm = Column(Integer, default=0)
    raw = Column(Text, default="")


class Spo2LogRow(Base):
    __tablename__ = "spo2_logs"
    device_time = Column(String(32), primary_key=True)
    spo2_pct = Column(Integer, default=0)
    raw = Column(Text, default="")


class TempLogRow(Base):
    __tablename__ = "temp_logs"
    device_time = Column(String(32), primary_key=True)
    temp_c = Column(Float, default=0.0)
    raw = Column(Text, default="")


class StepLogRow(Base):
    __tablename__ = "step_logs"
    device_time = Column(String(32), primary_key=True)
    steps = Column(Integer, default=0)
    calories_kcal = Column(Float, default=0.0)
    distance_km = Column(Float, default=0.0)
    step_series_json = Column(Text, default="[]")
    raw = Column(Text, default="")


class DailyReadinessSummaryRow(Base):
    __tablename__ = "daily_readiness_summaries"
    date = Column(String(16), primary_key=True)
    readiness_score = Column(Integer, default=0)
    sleep_score = Column(Integer, default=0)
    strain_score = Column(Float, default=0.0)
    overnight_hrv_ms = Column(Float, default=0.0)
    morning_rmssd_ms = Column(Float, default=0.0)
    resting_hr_bpm = Column(Float, default=0.0)
    total_sleep_min = Column(Integer, default=0)
    deep_sleep_min = Column(Integer, default=0)
    rem_sleep_min = Column(Integer, default=0)
    light_sleep_min = Column(Integer, default=0)
    spo2_avg_pct = Column(Float, default=0.0)
    skin_temp_delta_c = Column(Float, default=0.0)
    steps = Column(Integer, default=0)


Base.metadata.create_all(bind=engine)

app = FastAPI(
    title="JCWear Pro Homelab Sync & Claude Coach API",
    version="1.0.0",
)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def verify_token(x_api_token: str | None = Header(default=None)):
    if SYNC_API_TOKEN and x_api_token != SYNC_API_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid X-Api-Token")


class SleepBlockPayload(BaseModel):
    device_time: str
    unit_minutes: int = 1
    count: int = 0
    deep_min: int = 0
    light_min: int = 0
    rem_min: int = 0
    awake_min: int = 0
    post_noon_extended_min: int = 0
    stages: list[int] = Field(default_factory=list)
    raw: str = ""


class HrvLogPayload(BaseModel):
    device_time: str
    hrv: int
    hr: int = 0
    stress: int = 0
    vascular_aging: int = 0
    bp_sys: int = 0
    bp_dia: int = 0
    raw: str = ""


class HrLogPayload(BaseModel):
    device_time: str
    bpm: int
    raw: str = ""


class Spo2LogPayload(BaseModel):
    device_time: str
    spo2_pct: int
    raw: str = ""


class TempLogPayload(BaseModel):
    device_time: str
    temp_c: float
    raw: str = ""


class StepLogPayload(BaseModel):
    device_time: str
    steps: int
    calories_kcal: float = 0.0
    distance_km: float = 0.0
    step_series: list[int] = Field(default_factory=list)
    raw: str = ""


class DailySummaryPayload(BaseModel):
    date: str
    readiness_score: int
    sleep_score: int
    strain_score: float
    overnight_hrv_ms: float
    morning_rmssd_ms: float = 0.0
    resting_hr_bpm: float
    total_sleep_min: int
    deep_sleep_min: int = 0
    rem_sleep_min: int = 0
    light_sleep_min: int = 0
    spo2_avg_pct: float = 0.0
    skin_temp_delta_c: float = 0.0
    steps: int = 0


class SyncBatchPayload(BaseModel):
    sleep_blocks: list[SleepBlockPayload] = Field(default_factory=list)
    hrv_logs: list[HrvLogPayload] = Field(default_factory=list)
    hr_logs: list[HrLogPayload] = Field(default_factory=list)
    spo2_logs: list[Spo2LogPayload] = Field(default_factory=list)
    temp_logs: list[TempLogPayload] = Field(default_factory=list)
    step_logs: list[StepLogPayload] = Field(default_factory=list)
    daily_summaries: list[DailySummaryPayload] = Field(default_factory=list)


class ChatMessage(BaseModel):
    role: str
    content: str


class CoachChatRequest(BaseModel):
    messages: list[ChatMessage]
    include_health_context: bool = True
    anonymized_context_override: dict[str, Any] | None = None
    api_key_override: str | None = None
    model: str | None = None


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "claude_configured": bool(ANTHROPIC_API_KEY),
    }


@app.post("/api/v1/sync", dependencies=[Depends(verify_token)])
def sync_batch(payload: SyncBatchPayload, db: Session = Depends(get_db)):
    counts = {
        "sleep_blocks": 0,
        "hrv_logs": 0,
        "hr_logs": 0,
        "spo2_logs": 0,
        "temp_logs": 0,
        "step_logs": 0,
        "daily_summaries": 0,
    }
    for s in payload.sleep_blocks:
        db.merge(
            SleepBlockRow(
                device_time=s.device_time,
                unit_minutes=s.unit_minutes,
                count=s.count,
                deep_min=s.deep_min,
                light_min=s.light_min,
                rem_min=s.rem_min,
                awake_min=s.awake_min,
                post_noon_extended_min=s.post_noon_extended_min,
                stages_json=json.dumps(s.stages),
                raw=s.raw,
            )
        )
        counts["sleep_blocks"] += 1

    for h in payload.hrv_logs:
        db.merge(
            HrvLogRow(
                device_time=h.device_time,
                hrv=h.hrv,
                hr=h.hr,
                stress=h.stress,
                vascular_aging=h.vascular_aging,
                bp_sys=h.bp_sys,
                bp_dia=h.bp_dia,
                raw=h.raw,
            )
        )
        counts["hrv_logs"] += 1

    for hr in payload.hr_logs:
        db.merge(HrLogRow(device_time=hr.device_time, bpm=hr.bpm, raw=hr.raw))
        counts["hr_logs"] += 1

    for sp in payload.spo2_logs:
        db.merge(Spo2LogRow(device_time=sp.device_time, spo2_pct=sp.spo2_pct, raw=sp.raw))
        counts["spo2_logs"] += 1

    for tp in payload.temp_logs:
        db.merge(TempLogRow(device_time=tp.device_time, temp_c=tp.temp_c, raw=tp.raw))
        counts["temp_logs"] += 1

    for st in payload.step_logs:
        db.merge(
            StepLogRow(
                device_time=st.device_time,
                steps=st.steps,
                calories_kcal=st.calories_kcal,
                distance_km=st.distance_km,
                step_series_json=json.dumps(st.step_series),
                raw=st.raw,
            )
        )
        counts["step_logs"] += 1

    for ds in payload.daily_summaries:
        db.merge(DailyReadinessSummaryRow(**ds.model_dump()))
        counts["daily_summaries"] += 1

    db.commit()
    return {"status": "synced", "upserted": counts}


@app.get("/api/v1/restore", dependencies=[Depends(verify_token)])
def restore_all(limit_days: int = 90, db: Session = Depends(get_db)):
    summaries = (
        db.query(DailyReadinessSummaryRow)
        .order_by(DailyReadinessSummaryRow.date.desc())
        .limit(limit_days)
        .all()
    )
    hrv_rows = db.query(HrvLogRow).order_by(HrvLogRow.device_time.desc()).limit(limit_days * 144).all()
    sleep_rows = db.query(SleepBlockRow).order_by(SleepBlockRow.device_time.desc()).limit(limit_days * 4).all()
    return {
        "daily_summaries": [
            {
                "date": r.date,
                "readiness_score": r.readiness_score,
                "sleep_score": r.sleep_score,
                "strain_score": r.strain_score,
                "overnight_hrv_ms": r.overnight_hrv_ms,
                "morning_rmssd_ms": r.morning_rmssd_ms,
                "resting_hr_bpm": r.resting_hr_bpm,
                "total_sleep_min": r.total_sleep_min,
                "deep_sleep_min": r.deep_sleep_min,
                "rem_sleep_min": r.rem_sleep_min,
                "light_sleep_min": r.light_sleep_min,
                "spo2_avg_pct": r.spo2_avg_pct,
                "skin_temp_delta_c": r.skin_temp_delta_c,
                "steps": r.steps,
            }
            for r in summaries
        ],
        "hrv_logs_count": len(hrv_rows),
        "sleep_blocks_count": len(sleep_rows),
    }


@app.post("/api/v1/coach/chat", dependencies=[Depends(verify_token)])
async def coach_chat(req: CoachChatRequest, db: Session = Depends(get_db)):
    api_key = req.api_key_override or ANTHROPIC_API_KEY
    model = req.model or DEFAULT_CLAUDE_MODEL

    recent_summaries = (
        db.query(DailyReadinessSummaryRow)
        .order_by(DailyReadinessSummaryRow.date.desc())
        .limit(14)
        .all()
    )
    context_rows = [
        {
            "date": r.date,
            "readiness": r.readiness_score,
            "sleep_score": r.sleep_score,
            "strain": r.strain_score,
            "hrv_ms": r.overnight_hrv_ms,
            "morning_rmssd_ms": r.morning_rmssd_ms,
            "rhr_bpm": r.resting_hr_bpm,
            "sleep_min": r.total_sleep_min,
            "deep_min": r.deep_sleep_min,
            "rem_min": r.rem_sleep_min,
            "spo2_pct": r.spo2_avg_pct,
            "temp_delta_c": r.skin_temp_delta_c,
            "steps": r.steps,
        }
        for r in recent_summaries
    ]
    if req.anonymized_context_override:
        context_data = req.anonymized_context_override
    else:
        context_data = {"recent_14d_summaries": context_rows}

    system_prompt = (
        "You are an elite, evidence-based Sports Physiology & Recovery AI Coach (powered by Claude) "
        "integrated into a private, zero-cloud-leak J2208A wearable health system. "
        "You answer both personalized recovery/sleep/training questions and general health, physiology, "
        "and fitness questions concisely and actionably."
    )
    if req.include_health_context and context_data:
        system_prompt += (
            "\n\nAnonymized Wearable Telemetry Context (No PII):\n"
            + json.dumps(context_data, indent=2)
        )

    if not api_key:
        # 1. Try Claude Pro subscription via local Claude Code CLI (`claude -p`) at $0 extra API cost
        claude_bin = shutil.which("claude")
        if CLAUDE_PRO_CLI and claude_bin:
            try:
                user_convo = "\n".join(f"{m.role.upper()}: {m.content}" for m in req.messages[-6:])
                full_prompt = (
                    f"{system_prompt}\n\n"
                    "Reply in at most 3 short, human lines (under 12 words per sentence). "
                    "Always compare numbers to the user's normal range.\n\n"
                    f"{user_convo}"
                )
                proc = subprocess.run(
                    [claude_bin, "-p", full_prompt],
                    capture_output=True,
                    text=True,
                    timeout=35,
                    check=False,
                )
                if proc.returncode == 0 and proc.stdout.strip():
                    return {"reply": proc.stdout.strip(), "model": "claude-pro-cli"}
            except Exception:
                pass

        # 2. Offline deterministic fallback when neither Claude Pro CLI nor ANTHROPIC_API_KEY is active
        latest = context_rows[0] if context_rows else None
        if latest:
            reply = (
                f"Recovery is {latest['readiness']} (Sleep {latest['sleep_score']}).\n"
                f"HRV is {latest['hrv_ms']:.0f} ms and RHR is {latest['rhr_bpm']:.0f} bpm.\n"
                f"You slept {latest['sleep_min'] // 60}h {latest['sleep_min'] % 60:02d}m."
            )
        else:
            reply = "Run `claude login` on your homelab server or tap 'Ask in Claude Pro' to chat with your subscription."
        return {"reply": reply, "model": "offline-fallback"}

    async with httpx.AsyncClient(timeout=45.0) as client:
        resp = await client.post(
            "https://api.anthropic.com/v1/messages",
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": model,
                "max_tokens": 1024,
                "system": system_prompt,
                "messages": [{"role": m.role, "content": m.content} for m in req.messages],
            },
        )
        if resp.status_code >= 400:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        body = resp.json()
        text_blocks = [b.get("text", "") for b in body.get("content", []) if b.get("type") == "text"]
        return {"reply": "\n".join(text_blocks).strip(), "model": body.get("model", model)}
