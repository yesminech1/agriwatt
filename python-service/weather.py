"""AgriWatt - Etape 2 (+ fiabilité étape 9) : météo Open-Meteo, cache CSV et scénarios de démo.

Fonction principale : get_weather(lat, lon, days=2, scenario=None) -> DataFrame
Colonnes : hour, tempC, irradianceWm2, et0Mm, precipMm
Ordre de priorité :
  1. scenario ("normal", "canicule", "nuageux") -> données simulées (démo, tests)
  2. Open-Meteo (internet) -> résultat sauvegardé dans cache/weather_<lat>_<lon>.csv
  3. Pas d'internet ou réponse invalide -> lecture du cache de ce lieu
  4. Pas de cache (ou cache illisible)  -> scénario canicule (la démo ne plante jamais)
La source utilisée est indiquée dans df.attrs["source"] ("api", "cache" ou "scenario").
"""
from datetime import datetime, timedelta
from pathlib import Path
import math

import pandas as pd
import requests

CACHE_DIR = Path(__file__).parent / "cache"
SCENARIO_FILE = CACHE_DIR / "scenario_canicule.csv"
OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
COLUMNS = ["hour", "tempC", "irradianceWm2", "et0Mm", "precipMm"]

# Paramètres des scénarios : temp min/max (°C), irradiance max (W/m2), ET0 max horaire (mm), pluie (mm/h)
SCENARIOS = {
    "normal":   {"tmin": 26, "tmax": 36, "irr": 900, "et0": 0.70, "rain": 0.0},
    "canicule": {"tmin": 30, "tmax": 44, "irr": 950, "et0": 0.85, "rain": 0.0},
    "nuageux":  {"tmin": 22, "tmax": 29, "irr": 320, "et0": 0.30, "rain": 0.2},
}


def _cache_file(lat: float, lon: float) -> Path:
    return CACHE_DIR / f"weather_{lat:.2f}_{lon:.2f}.csv"


def _today() -> datetime:
    return datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)


def fetch_open_meteo(lat: float, lon: float, days: int = 2) -> pd.DataFrame:
    """Appelle Open-Meteo et renvoie un DataFrame horaire (lève une exception si échec)."""
    params = {
        "latitude": lat,
        "longitude": lon,
        "hourly": "temperature_2m,shortwave_radiation,et0_fao_evapotranspiration,precipitation",
        "forecast_days": days,
        "timezone": "Africa/Tunis",
    }
    r = requests.get(OPEN_METEO_URL, params=params, timeout=5)
    r.raise_for_status()
    h = r.json()["hourly"]
    df = pd.DataFrame({
        "hour": h["time"],                          # déjà au format "2026-07-15T13:00"
        "tempC": h["temperature_2m"],
        "irradianceWm2": h["shortwave_radiation"],
        "et0Mm": h["et0_fao_evapotranspiration"],
        "precipMm": h["precipitation"],
    })
    if len(df) < 24 * days:
        raise ValueError(f"Réponse incomplète : {len(df)} heures")
    return df.fillna(0.0)


def make_scenario(kind: str = "canicule", days: int = 2, start: datetime | None = None) -> pd.DataFrame:
    """Journée simulée : soleil en cloche (6h-20h), température max vers 15h."""
    p = SCENARIOS[kind]
    start = start or _today()
    mid, amp = (p["tmax"] + p["tmin"]) / 2, (p["tmax"] - p["tmin"]) / 2
    rows = []
    for i in range(24 * days):
        t = start + timedelta(hours=i)
        h = t.hour
        sun = max(0.0, math.sin(math.pi * (h - 6) / 14)) if 6 <= h <= 20 else 0.0
        rows.append({
            "hour": t.strftime("%Y-%m-%dT%H:%M"),
            "tempC": round(mid + amp * math.sin(math.pi * (h - 9) / 12), 1),
            "irradianceWm2": round(p["irr"] * sun, 1),
            "et0Mm": round(p["et0"] * sun, 3),
            "precipMm": p["rain"] if 10 <= h < 14 else 0.0,
        })
    return pd.DataFrame(rows, columns=COLUMNS)


def make_scenario_canicule(days: int = 2, start: datetime | None = None) -> pd.DataFrame:
    """Raccourci gardé pour compatibilité."""
    return make_scenario("canicule", days, start)


def _load_scenario(kind: str, days: int) -> pd.DataFrame:
    """Canicule : lue depuis cache/scenario_canicule.csv (créé si absent). Autres : générées."""
    if kind != "canicule":
        return make_scenario(kind, days)
    CACHE_DIR.mkdir(exist_ok=True)
    try:
        if not SCENARIO_FILE.exists():
            make_scenario("canicule", days=7).to_csv(SCENARIO_FILE, index=False)
        df = pd.read_csv(SCENARIO_FILE).head(24 * days).copy()
        if len(df) < 24 * days or list(df.columns) != COLUMNS:
            raise ValueError("fichier scénario incomplet")
    except Exception:
        return make_scenario("canicule", days)
    # recale les dates sur aujourd'hui pour que la démo soit toujours "à jour"
    df["hour"] = [(_today() + timedelta(hours=i)).strftime("%Y-%m-%dT%H:%M") for i in range(len(df))]
    return df


def get_weather(lat: float, lon: float, days: int = 2, scenario: str | None = None) -> pd.DataFrame:
    """Point d'entrée unique utilisé par /forecast."""
    if scenario:
        df = _load_scenario(scenario, days)
        df.attrs["source"] = "scenario"
        return df

    CACHE_DIR.mkdir(exist_ok=True)
    cache = _cache_file(lat, lon)
    try:
        df = fetch_open_meteo(lat, lon, days)
        df.to_csv(cache, index=False)
        df.attrs["source"] = "api"
        return df
    except Exception as e:  # pas d'internet, timeout, réponse invalide...
        print(f"[weather] Open-Meteo indisponible ({type(e).__name__}) -> plan B")

    try:
        if cache.exists():
            df = pd.read_csv(cache).head(24 * days).fillna(0.0)
            if list(df.columns) == COLUMNS and len(df) > 0:
                df.attrs["source"] = "cache"
                return df
    except Exception as e:
        print(f"[weather] cache illisible ({type(e).__name__}) -> scénario canicule")

    df = _load_scenario("canicule", days)
    df.attrs["source"] = "scenario"
    return df


if __name__ == "__main__":
    # Test rapide : python weather.py
    for sc in (None, "normal", "canicule", "nuageux"):
        df = get_weather(33.92, 8.13, days=2, scenario=sc)
        print(f"scenario={str(sc):9} | source={df.attrs['source']:8} | {len(df)} h | "
              f"Tmax {df['tempC'].max()} °C | Irr max {df['irradianceWm2'].max()} W/m2 | "
              f"ET0 j1 {df['et0Mm'].head(24).sum():.1f} mm")