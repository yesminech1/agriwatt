"""AgriWatt - tests automatiques du service Python (météo réelle uniquement).

Lancer depuis python-service :  python -m pytest -v
Open-Meteo est remplacé ici par un faux serveur (fixture) : les tests n'ont pas besoin d'internet.
Les séries utilisées dans les tests sont des données de TEST, jamais renvoyées par l'application.
"""
import math
import time
from datetime import datetime

import pandas as pd
import pytest
from fastapi.testclient import TestClient

import weather
from main import app
from outage import outage_risk
from pv import pv_kw
from water import water_need_m3

client = TestClient(app)
FORECAST_FIELDS = {"hour", "tempC", "irradianceWm2", "et0Mm", "pvKw", "outageRisk", "waterNeedM3"}
ANOMALY_FIELDS = {"timestamp", "powerKw", "isAnomaly", "score", "reason"}
TOZEUR, SFAX = (33.92, 8.13), (34.74, 10.76)
Q = "crop=olive&areaM2=20000&panelAreaM2=24&panelEfficiency=0.18&days=2"


def fake_hourly(lat, days=2, cloud=1.0):
    """Journée d'été type renvoyée par le FAUX Open-Meteo (dépend de la latitude pour distinguer les lieux)."""
    today = datetime.now().strftime("%Y-%m-%d")
    t, temp, irr, et0 = [], [], [], []
    for i in range(24 * days):
        h = i % 24
        day = pd.Timestamp(today) + pd.Timedelta(days=i // 24)
        t.append(f"{day:%Y-%m-%d}T{h:02d}:00")
        sun = max(0.0, math.sin(math.pi * (h - 6) / 14)) if 6 <= h <= 20 else 0.0
        temp.append(round(30 + (40 - lat) + 8 * math.sin(math.pi * (h - 9) / 12), 1))
        irr.append(round(920 * sun * cloud, 1))
        et0.append(round(0.75 * sun * cloud, 3))
    return {"hourly": {"time": t, "temperature_2m": temp, "shortwave_radiation": irr,
                       "et0_fao_evapotranspiration": et0, "precipitation": [0.0] * len(t)}}


class FakeOpenMeteo:
    def __init__(self):
        self.calls, self.down, self.cloud = [], False, 1.0

    def get(self, url, params=None, timeout=None):
        self.calls.append((params["latitude"], params["longitude"]))
        if self.down:
            raise ConnectionError("pas d'internet")
        data = fake_hourly(params["latitude"], params["forecast_days"], self.cloud)

        class R:
            def raise_for_status(self): pass
            def json(self): return data
        return R()


@pytest.fixture(autouse=True)
def om(monkeypatch, tmp_path):
    fake = FakeOpenMeteo()
    monkeypatch.setattr(weather.requests, "get", fake.get)
    monkeypatch.setattr(weather, "CACHE_DIR", tmp_path)
    return fake


def hours_at(pos, extra=""):
    r = client.get(f"/forecast?lat={pos[0]}&lon={pos[1]}&{Q}{extra}")
    assert r.status_code == 200, r.text
    return r.json()


# ---------- 1. Contrat ----------
def test_health():
    assert client.get("/health").json() == {"status": "ok"}


def test_forecast_respecte_le_contrat():
    body = hours_at(TOZEUR)
    assert len(body["hours"]) == 48 and body["source"] == "openmeteo"
    assert (body["lat"], body["lon"]) == TOZEUR
    for h in body["hours"]:
        assert set(h.keys()) == FORECAST_FIELDS
        assert len(h["hour"]) == 16 and h["hour"][10] == "T"


def test_parametres_invalides_422():
    assert client.get(f"/forecast?lat=33.9&lon=8.1&crop=mais").status_code == 422
    assert client.get("/forecast?forceOutageStart=30").status_code == 422
    assert client.get("/forecast?lat=120&lon=8").status_code == 422
    assert client.get("/forecast?lat=34&lon=200").status_code == 422


def test_plus_aucun_scenario_ni_demo():
    assert client.get("/anomalies/demo").status_code == 404
    paths = app.openapi()["paths"]
    assert "scenario" not in str(paths["/forecast"])


# ---------- 2. Météo réelle à la position ----------
def test_coordonnees_transmises_a_open_meteo(om):
    t, s = hours_at(TOZEUR), hours_at(SFAX)
    assert om.calls == [TOZEUR, SFAX]
    assert t["hours"][14]["tempC"] != s["hours"][14]["tempC"]


def test_valeurs_realistes():
    for h in hours_at(TOZEUR)["hours"]:
        assert 0 <= h["pvKw"] <= 3.5 and 0 <= h["outageRisk"] <= 1 and h["waterNeedM3"] >= 0
        if int(h["hour"][11:13]) in (0, 1, 2, 3, 22, 23):
            assert h["pvKw"] == 0


def test_journee_couverte_moins_de_solaire(om):
    clair = max(h["pvKw"] for h in hours_at(TOZEUR)["hours"])
    om.cloud = 0.3
    couvert = max(h["pvKw"] for h in hours_at(TOZEUR)["hours"])
    assert couvert < 0.5 * clair


def test_coupure_prevue():
    for h in hours_at(TOZEUR, "&forceOutageStart=14&forceOutageEnd=19")["hours"][:24]:
        hr = int(h["hour"][11:13])
        assert (h["outageRisk"] == 1.0) if 14 <= hr < 19 else (h["outageRisk"] < 1.0)


# ---------- 3. Fiabilité : jamais de données fictives ----------
def test_hors_ligne_avec_cache_du_jour(om):
    hours_at(SFAX)                      # remplit le cache du jour
    om.down = True
    body = hours_at(SFAX)
    assert body["source"] == "cache" and len(body["hours"]) == 48


def test_hors_ligne_sans_cache_renvoie_503(om):
    om.down = True
    r = client.get(f"/forecast?lat={SFAX[0]}&lon={SFAX[1]}&{Q}")
    assert r.status_code == 503 and "indisponibles" in r.json()["detail"]


def test_cache_perime_refuse(om, tmp_path):
    old = pd.DataFrame(fake_hourly(SFAX[0])["hourly"]).rename(columns={
        "time": "hour", "temperature_2m": "tempC", "shortwave_radiation": "irradianceWm2",
        "et0_fao_evapotranspiration": "et0Mm", "precipitation": "precipMm"})
    old["hour"] = old["hour"].str.replace(datetime.now().strftime("%Y-%m-%d"), "2020-01-01")
    old.to_csv(tmp_path / "weather_34.74_10.76.csv", index=False)
    om.down = True
    assert client.get(f"/forecast?lat={SFAX[0]}&lon={SFAX[1]}&{Q}").status_code == 503


def test_forecast_rapide():
    t = time.time()
    hours_at(TOZEUR)
    assert time.time() - t < 3


# ---------- 4. Anomalies sur mesures fournies ----------
def series_de_test():
    """Mesures de TEST (7 jours, pas 15 min) : pompe 5 kW de 8h à 17h + 3 épisodes anormaux."""
    pts = []
    for i in range(7 * 96):
        d, m = divmod(i, 96)
        h = m / 4
        p = 5.0 + 0.1 * math.sin(i) if 8 <= h < 17 else 0.0
        if d == 1 and 10 <= h < 11.5: p = 6.8          # usure
        if d == 3 and 14 <= h < 15: p = 1.3            # marche à vide
        if d == 5 and 9 <= h < 11: p = 3.8             # fuite
        pts.append({"timestamp": f"2026-07-{10 + d:02d}T{int(h):02d}:{int(m % 4 * 15):02d}", "powerKw": round(p, 2)})
    return pts


def test_detection_sur_mesures_importees():
    pts = client.post("/anomalies/detect", json={"points": series_de_test()}).json()["points"]
    assert len(pts) == 672 and all(set(p.keys()) == ANOMALY_FIELDS for p in pts)
    anomalies = [p for p in pts if p["isAnomaly"]]
    assert 15 <= len(anomalies) <= 20
    reasons = " ".join(p["reason"] for p in anomalies)
    assert "usure" in reasons and "vide" in reasons and "fuite" in reasons


def test_detection_petit_jeu_et_corps_invalide():
    body = {"points": [{"timestamp": f"2026-07-10T{h:02d}:00", "powerKw": p}
                       for h, p in [(8, 5.0), (9, 5.1), (10, 6.9), (11, 1.1), (12, 4.9)]]}
    pts = client.post("/anomalies/detect", json=body).json()["points"]
    assert [p["isAnomaly"] for p in pts] == [False, False, True, True, False]
    assert client.post("/anomalies/detect", json={"x": 1}).status_code == 422


# ---------- 5. Formules ----------
def test_formules():
    assert pv_kw(1000, 24, 0.18) == pytest.approx(3.456)
    assert pv_kw(-5, 24) == 0
    assert water_need_m3(9, "olive", 20000) == pytest.approx(130.0)
    assert outage_risk(30, 3) == 0.1
    assert outage_risk(46, 15) == 0.95
    assert outage_risk(25, 10, 9, 12) == 1.0
