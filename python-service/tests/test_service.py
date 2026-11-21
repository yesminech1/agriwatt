"""AgriWatt - Etape 9 : tests automatiques du service Python.

Lancer depuis python-service :  python -m pytest -v
Les tests n'ont PAS besoin d'internet (scénarios simulés + Open-Meteo coupé artificiellement).
"""
import time

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
DEMO = "/forecast?lat=33.92&lon=8.13&crop=olive&areaM2=20000&panelAreaM2=24&panelEfficiency=0.18&days=2"


def get_hours(scenario: str, extra: str = "") -> list:
    r = client.get(f"{DEMO}&scenario={scenario}{extra}")
    assert r.status_code == 200, r.text
    return r.json()["hours"]


def daily_water(hours: list) -> float:
    return sum(h["waterNeedM3"] for h in hours[:24])


# ---------- 1. Contrat ----------
def test_health():
    r = client.get("/health")
    assert r.status_code == 200 and r.json() == {"status": "ok"}


@pytest.mark.parametrize("scenario", ["normal", "canicule", "nuageux"])
def test_forecast_respecte_le_contrat(scenario):
    hours = get_hours(scenario)
    assert len(hours) == 48
    for h in hours:
        assert set(h.keys()) == FORECAST_FIELDS
        assert len(h["hour"]) == 16 and h["hour"][10] == "T"      # format "2026-07-15T13:00"


def test_culture_inconnue_renvoie_422():
    assert client.get(f"{DEMO}&crop=mais".replace("crop=olive&", "")).status_code == 422


def test_heure_de_coupure_invalide_renvoie_422():
    assert client.get("/forecast?forceOutageStart=30").status_code == 422


# ---------- 2. Réalisme des valeurs (3 cas) ----------
@pytest.mark.parametrize("scenario", ["normal", "canicule", "nuageux"])
def test_valeurs_dans_des_bornes_physiques(scenario):
    for h in get_hours(scenario):
        assert 0 <= h["pvKw"] <= 3.5          # 24 m2 x 18 % = 4,3 kWc -> max ~3,3 kW avec pertes
        assert 0 <= h["irradianceWm2"] <= 1100
        assert 0 <= h["outageRisk"] <= 1
        assert h["waterNeedM3"] >= 0
        if int(h["hour"][11:13]) in (0, 1, 2, 3, 22, 23):
            assert h["pvKw"] == 0              # pas de solaire la nuit


def test_journee_normale():
    hours = get_hours("normal")
    assert 2.5 <= max(h["pvKw"] for h in hours) <= 3.5
    assert 40 <= daily_water(hours) <= 120     # 2 ha d'oliviers, été normal


def test_canicule_plus_exigeante_que_normale():
    can, nor = get_hours("canicule"), get_hours("normal")
    assert max(h["tempC"] for h in can) >= 43
    assert daily_water(can) > daily_water(nor)
    risk = lambda hs: sum(h["outageRisk"] for h in hs[13:17]) / 4       # 13h-17h
    assert risk(can) > risk(nor)


def test_journee_nuageuse():
    nua, nor = get_hours("nuageux"), get_hours("normal")
    assert max(h["pvKw"] for h in nua) < 0.5 * max(h["pvKw"] for h in nor)
    assert daily_water(nua) < 0.6 * daily_water(nor)


def test_coupure_forcee():
    hours = get_hours("canicule", "&forceOutageStart=14&forceOutageEnd=19")
    for h in hours[:24]:
        hr = int(h["hour"][11:13])
        assert (h["outageRisk"] == 1.0) if 14 <= hr < 19 else (h["outageRisk"] < 1.0)


# ---------- 3. Fiabilité ----------
def test_hors_ligne_sans_cache(monkeypatch, tmp_path):
    """Open-Meteo coupé + aucun cache -> le service répond quand même (scénario canicule)."""
    def no_internet(*a, **k):
        raise ConnectionError("pas d'internet")
    monkeypatch.setattr(weather.requests, "get", no_internet)
    monkeypatch.setattr(weather, "CACHE_DIR", tmp_path)
    monkeypatch.setattr(weather, "SCENARIO_FILE", tmp_path / "scenario_canicule.csv")
    r = client.get(DEMO)
    assert r.status_code == 200 and len(r.json()["hours"]) == 48


def test_hors_ligne_avec_cache(monkeypatch, tmp_path):
    """Un cache existant est relu quand Open-Meteo ne répond pas."""
    monkeypatch.setattr(weather, "CACHE_DIR", tmp_path)
    weather.make_scenario("nuageux").to_csv(tmp_path / "weather_33.92_8.13.csv", index=False)
    monkeypatch.setattr(weather.requests, "get", lambda *a, **k: (_ for _ in ()).throw(TimeoutError()))
    df = weather.get_weather(33.92, 8.13, days=2)
    assert df.attrs["source"] == "cache" and df["irradianceWm2"].max() == 320


def test_forecast_rapide():
    t = time.time()
    get_hours("canicule")
    assert time.time() - t < 3


# ---------- 4. Anomalies ----------
def test_anomalies_demo():
    r = client.get("/anomalies/demo?days=7")
    assert r.status_code == 200
    pts = r.json()["points"]
    assert len(pts) == 672
    anomalies = [p for p in pts if p["isAnomaly"]]
    assert 15 <= len(anomalies) <= 25
    for p in pts:
        assert set(p.keys()) == ANOMALY_FIELDS
    for a in anomalies:
        assert a["score"] < 0 and any(m in a["reason"] for m in ("usure", "fuite", "vide"))


def test_anomalies_detect():
    body = {"points": [{"timestamp": f"2026-07-10T{h:02d}:00", "powerKw": p}
                       for h, p in [(8, 5.0), (9, 5.1), (10, 6.9), (11, 1.1), (12, 4.9)]]}
    pts = client.post("/anomalies/detect", json=body).json()["points"]
    assert [p["isAnomaly"] for p in pts] == [False, False, True, True, False]
    assert "usure" in pts[2]["reason"] and "vide" in pts[3]["reason"]


def test_anomalies_detect_corps_invalide():
    assert client.post("/anomalies/detect", json={"x": 1}).status_code == 422


# ---------- 5. Fonctions de calcul ----------
def test_formules():
    assert pv_kw(1000, 24, 0.18) == pytest.approx(3.456)
    assert pv_kw(-5, 24) == 0
    assert water_need_m3(9, "olive", 20000) == pytest.approx(130.0)
    assert outage_risk(30, 3) == 0.1
    assert outage_risk(46, 15) == 0.95
    assert outage_risk(25, 10, 9, 12) == 1.0