"""AgriWatt - Service Python (Personne A).

Etape 6 : /forecast branché sur les vraies fonctions (weather, pv, water, outage).
/anomalies : encore une détection provisoire (Isolation Forest à l'étape 8).
Le format des réponses respecte exactement le contrat d'interface (section 4).
"""
from datetime import datetime, timedelta
import random
import statistics
from typing import List, Literal, Optional

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from outage import outage_risk
from pv import pv_kw
from water import water_need_m3
from weather import get_weather

app = FastAPI(title="AgriWatt - Service Python", version="0.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------- Modèles (contrat) ----------
class HourForecast(BaseModel):
    hour: str
    tempC: float
    irradianceWm2: float
    et0Mm: float
    pvKw: float
    outageRisk: float
    waterNeedM3: float


class ForecastResponse(BaseModel):
    hours: List[HourForecast]


class PumpPointIn(BaseModel):
    timestamp: str
    powerKw: float


class DetectRequest(BaseModel):
    points: List[PumpPointIn]


class AnomalyPoint(BaseModel):
    timestamp: str
    powerKw: float
    isAnomaly: bool
    score: float
    reason: str


class AnomalyResponse(BaseModel):
    points: List[AnomalyPoint]


# ---------- Endpoints ----------
@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/forecast", response_model=ForecastResponse)
def forecast(
    lat: float = 33.92,
    lon: float = 8.13,
    crop: Literal["olive", "datte", "tomate", "ble"] = "olive",
    areaM2: float = 20000,
    panelAreaM2: float = 24,
    panelEfficiency: float = 0.18,
    days: int = Query(2, ge=1, le=7),
    scenario: Optional[Literal["canicule"]] = None,
    forceOutageStart: Optional[int] = Query(None, ge=0, le=23),
    forceOutageEnd: Optional[int] = Query(None, ge=1, le=24),
):
    """Prévision horaire réelle : météo (Open-Meteo / cache / scénario) + solaire + eau + risque."""
    df = get_weather(lat, lon, days=days, scenario=scenario)
    hours = []
    for row in df.itertuples(index=False):
        h = int(str(row.hour)[11:13])
        hours.append(HourForecast(
            hour=str(row.hour),
            tempC=round(float(row.tempC), 1),
            irradianceWm2=round(float(row.irradianceWm2), 1),
            et0Mm=round(float(row.et0Mm), 3),
            pvKw=round(pv_kw(float(row.irradianceWm2), panelAreaM2, panelEfficiency), 3),
            outageRisk=outage_risk(float(row.tempC), h, forceOutageStart, forceOutageEnd),
            waterNeedM3=round(water_need_m3(float(row.et0Mm), crop, areaM2), 3),
        ))
    return ForecastResponse(hours=hours)


@app.get("/anomalies/demo", response_model=AnomalyResponse)
def anomalies_demo(days: int = Query(7, ge=1, le=30)):
    """FACTICE : pompe horaire ~5 kW de 8h à 17h, anomalies injectées."""
    rng = random.Random(42)
    start = (datetime.now() - timedelta(days=days)).replace(minute=0, second=0, microsecond=0, hour=0)
    pts = []
    for i in range(24 * days):
        t = start + timedelta(hours=i)
        on = 8 <= t.hour < 17
        p = round(5.0 + rng.gauss(0, 0.15), 2) if on else 0.0
        pts.append(PumpPointIn(timestamp=t.strftime("%Y-%m-%dT%H:%M"), powerKw=p))
    # anomalies injectées (jour 2 : usure, jour 4 : marche à vide, jour 6 : fuite)
    for day, hour, val in [(1, 10, 6.9), (1, 11, 6.8), (3, 14, 1.2), (5, 9, 3.6)]:
        idx = day * 24 + hour
        if idx < len(pts):
            pts[idx].powerKw = val
    return _detect_simple(pts)


@app.post("/anomalies/detect", response_model=AnomalyResponse)
def anomalies_detect(req: DetectRequest):
    return _detect_simple(req.points)


# ---------- Détection provisoire (remplacée par Isolation Forest à l'étape 8) ----------
def _detect_simple(points: List[PumpPointIn]) -> AnomalyResponse:
    on_vals = [p.powerKw for p in points if p.powerKw > 0.5]
    median = statistics.median(on_vals) if on_vals else 5.0
    out = []
    for p in points:
        reason, is_anom, score = "Fonctionnement normal", False, 0.1
        if p.powerKw > 0:
            dev = (p.powerKw - median) / median
            if dev > 0.25:
                is_anom, reason = True, f"Consommation +{dev*100:.0f} % par rapport à la normale (usure probable)"
            elif p.powerKw < 0.4 * median:
                is_anom, reason = True, f"Puissance très basse ({p.powerKw} kW) : marche à vide probable"
            elif dev < -0.2:
                is_anom, reason = True, f"Consommation {dev*100:.0f} % par rapport à la normale (fuite possible)"
            score = round(-abs(dev) / 2, 2) if is_anom else round(0.1 - abs(dev) / 2, 2)
        elif p.powerKw == 0:
            reason = "Pompe à l'arrêt"
        out.append(AnomalyPoint(timestamp=p.timestamp, powerKw=p.powerKw,
                                isAnomaly=is_anom, score=score, reason=reason))
    return AnomalyResponse(points=out)