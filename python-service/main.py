"""AgriWatt - Service Python (Personne A).

Etape 6 : /forecast branché sur les vraies fonctions (weather, pv, water, outage).
Etape 8 : /anomalies utilise Isolation Forest sur des données de pompe simulées.
Le format des réponses respecte exactement le contrat d'interface (section 4).
"""
from typing import List, Literal, Optional

from fastapi import FastAPI, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from anomaly import detect_anomalies
from outage import outage_risk
from pv import pv_kw
from simulate_pump import simulate_pump
from water import water_need_m3
from weather import get_weather

app = FastAPI(title="AgriWatt - Service Python", version="0.3.0")

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
    """Données de pompe simulées (pas 15 min) + détection Isolation Forest."""
    sim = simulate_pump(days=days)
    return AnomalyResponse(points=detect_anomalies(list(zip(sim["timestamp"], sim["powerKw"]))))


@app.post("/anomalies/detect", response_model=AnomalyResponse)
def anomalies_detect(req: DetectRequest):
    """Détection sur des mesures fournies par l'appelant."""
    return AnomalyResponse(points=detect_anomalies([(p.timestamp, p.powerKw) for p in req.points]))