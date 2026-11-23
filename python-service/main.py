"""AgriWatt - Service Python (Personne A).

/forecast : météo RÉELLE (Open-Meteo, cache du jour) à la position de la ferme -> solaire, eau, risque de coupure.
            Aucun scénario fictif : si la météo est indisponible, réponse 503 explicite.
/anomalies/detect : Isolation Forest sur des mesures de pompe fournies (import CSV côté application).
/detection/analyze : stress hydrique de l'olivier sur photo (MobileNetV3 entraîné par ml/train.py, chargé une fois).
/detection/model   : état du modèle, plantes prises en charge et métriques mesurées sur le jeu de test.
Le format des réponses respecte exactement le contrat d'interface (section 4).
"""
from typing import List, Literal, Optional

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from anomaly import detect_anomalies
from detection import ModelUnavailable, detector
from stress_model import MAX_IMAGE_BYTES, InvalidImage
from outage import outage_risk
from pv import pv_kw
from water import water_need_m3
from weather import WeatherUnavailable, get_weather

app = FastAPI(title="AgriWatt - Service Python", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)



@app.exception_handler(Exception)
async def unexpected_error(request: Request, exc: Exception):
    """Toute erreur imprévue renvoie un JSON clair (500) au lieu de faire planter l'appel."""
    print(f"[erreur] {request.url.path} : {type(exc).__name__} : {exc}")
    return JSONResponse(status_code=500,
                        content={"detail": f"Erreur interne du service Python : {type(exc).__name__}"})


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
    # Ajouts compatibles (champs supplémentaires, ignorés par les anciens clients) :
    # d'où vient la météo et pour quelle position elle a été calculée.
    source: Optional[str] = None      # openmeteo | cache (prévisions du jour enregistrées)
    lat: Optional[float] = None
    lon: Optional[float] = None


SOURCE_LABELS = {"api": "openmeteo", "cache": "cache"}
MAX_POINTS = 50_000


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
    lat: float = Query(33.92, ge=-90, le=90),
    lon: float = Query(8.13, ge=-180, le=180),
    crop: Literal["olive", "datte", "tomate", "ble"] = "olive",
    areaM2: float = 20000,
    panelAreaM2: float = 24,
    panelEfficiency: float = 0.18,
    days: int = Query(2, ge=1, le=7),
    forceOutageStart: Optional[int] = Query(None, ge=0, le=23),
    forceOutageEnd: Optional[int] = Query(None, ge=1, le=24),
):
    """Prévision horaire réelle à la position de la ferme : météo Open-Meteo + solaire + eau + risque de coupure (proxy)."""
    try:
        df = get_weather(lat, lon, days=days)
    except WeatherUnavailable as e:
        raise HTTPException(status_code=503, detail=str(e))
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
    return ForecastResponse(hours=hours, source=SOURCE_LABELS.get(df.attrs.get("source"), "inconnue"),
                            lat=lat, lon=lon)


@app.post("/anomalies/detect", response_model=AnomalyResponse)
def anomalies_detect(req: DetectRequest):
    """Détection sur des mesures réelles fournies par l'appelant (import CSV de la ferme)."""
    if len(req.points) > MAX_POINTS:
        raise HTTPException(status_code=413, detail=f"Trop de mesures (maximum {MAX_POINTS}).")
    return AnomalyResponse(points=detect_anomalies([(p.timestamp, p.powerKw) for p in req.points]))


@app.get("/detection/model")
def detection_model():
    """État du modèle de stress hydrique (aucun résultat inventé : métriques lues dans les métadonnées d'entraînement)."""
    return detector.info()


@app.post("/detection/analyze")
async def detection_analyze(file: UploadFile = File(...), plantType: str = Form("olivier")):
    """Analyse une photo JPG/PNG d'olivier et renvoie la sortie réelle du modèle."""
    data = await file.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail=f"Image trop volumineuse (maximum {MAX_IMAGE_BYTES // (1024 * 1024)} Mo).")
    try:
        return detector.analyze(data, plantType.strip().lower())
    except InvalidImage as e:
        raise HTTPException(status_code=400, detail=str(e))
    except ModelUnavailable as e:
        raise HTTPException(status_code=503, detail=str(e))
