"""AgriWatt - Etape 2 : météo réelle (Open-Meteo) avec cache CSV et scénario canicule.

Fonction principale : get_weather(lat, lon, days=2, scenario=None) -> DataFrame
Colonnes : hour, tempC, irradianceWm2, et0Mm, precipMm
Ordre de priorité :
  1. scenario="canicule"  -> cache/scenario_canicule.csv (démo)
  2. Open-Meteo (internet) -> résultat sauvegardé dans cache/weather_<lat>_<lon>.csv
  3. Pas d'internet       -> lecture du cache de ce lieu
  4. Pas de cache non plus -> scénario canicule (la démo ne plante jamais)
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


def _cache_file(lat: float, lon: float) -> Path:
    return CACHE_DIR / f"weather_{lat:.2f}_{lon:.2f}.csv"


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
    return df.fillna(0.0)


def make_scenario_canicule(days: int = 2, start: datetime | None = None) -> pd.DataFrame:
    """Canicule simulée à Tozeur : 44 °C l'après-midi, ciel clair (~950 W/m2), pas de pluie."""
    if start is None:
        start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    rows = []
    for i in range(24 * days):
        t = start + timedelta(hours=i)
        h = t.hour
        sun = max(0.0, math.sin(math.pi * (h - 6) / 14)) if 6 <= h <= 20 else 0.0
        # température : min ~30 °C vers 3h, max 44 °C vers 15h
        temp = 37 + 7 * math.sin(math.pi * (h - 9) / 12)
        rows.append({
            "hour": t.strftime("%Y-%m-%dT%H:%M"),
            "tempC": round(temp, 1),
            "irradianceWm2": round(950 * sun, 1),
            "et0Mm": round(0.85 * sun, 3),          # ~7,5 mm/jour, typique canicule saharienne
            "precipMm": 0.0,
        })
    return pd.DataFrame(rows, columns=COLUMNS)


def _load_scenario(days: int) -> pd.DataFrame:
    """Lit le scénario canicule (le crée s'il n'existe pas) et le recale sur aujourd'hui."""
    CACHE_DIR.mkdir(exist_ok=True)
    if not SCENARIO_FILE.exists():
        make_scenario_canicule(days=7).to_csv(SCENARIO_FILE, index=False)
    df = pd.read_csv(SCENARIO_FILE)
    # recale les dates sur aujourd'hui pour que la démo soit toujours "à jour"
    start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    df = df.head(24 * days).copy()
    df["hour"] = [(start + timedelta(hours=i)).strftime("%Y-%m-%dT%H:%M") for i in range(len(df))]
    return df


def get_weather(lat: float, lon: float, days: int = 2, scenario: str | None = None) -> pd.DataFrame:
    """Point d'entrée unique utilisé par /forecast."""
    if scenario == "canicule":
        df = _load_scenario(days)
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

    if cache.exists():
        df = pd.read_csv(cache).head(24 * days)
        df.attrs["source"] = "cache"
        return df

    df = _load_scenario(days)
    df.attrs["source"] = "scenario"
    return df


if __name__ == "__main__":
    # Test rapide : python weather.py
    for sc in (None, "canicule"):
        df = get_weather(33.92, 8.13, days=2, scenario=sc)
        print(f"\n=== scenario={sc} | source={df.attrs['source']} | {len(df)} heures ===")
        print(df.iloc[10:16].to_string(index=False))
        print(f"Temp max : {df['tempC'].max()} °C | Irradiance max : {df['irradianceWm2'].max()} W/m2"
              f" | ET0 jour 1 : {df['et0Mm'].head(24).sum():.1f} mm")