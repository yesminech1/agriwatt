"""AgriWatt - Météo RÉELLE (Open-Meteo) pour la position d'une ferme, avec cache local.

Fonction principale : get_weather(lat, lon, days=2) -> DataFrame
Colonnes : hour, tempC, irradianceWm2, et0Mm, precipMm
Ordre :
  1. Open-Meteo (internet) -> résultat sauvegardé dans cache/weather_<lat>_<lon>.csv
  2. Open-Meteo indisponible -> cache de CETTE position, seulement s'il couvre la période demandée
     (prévisions récupérées aujourd'hui pour aujourd'hui et les jours suivants)
  3. Sinon -> WeatherUnavailable : AUCUNE donnée fictive n'est jamais renvoyée à la place.
La source utilisée est indiquée dans df.attrs["source"] ("api" ou "cache").
"""
from datetime import datetime
from pathlib import Path

import pandas as pd
import requests

CACHE_DIR = Path(__file__).parent / "cache"
OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
COLUMNS = ["hour", "tempC", "irradianceWm2", "et0Mm", "precipMm"]


class WeatherUnavailable(Exception):
    """Météo réelle indisponible pour la position demandée (réseau, service ou cache périmé)."""


def _cache_file(lat: float, lon: float) -> Path:
    return CACHE_DIR / f"weather_{lat:.2f}_{lon:.2f}.csv"


def _today_str() -> str:
    return datetime.now().strftime("%Y-%m-%d")


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
        "hour": h["time"],                          # déjà au format "2026-07-15T13:00", heure de Tunis
        "tempC": h["temperature_2m"],
        "irradianceWm2": h["shortwave_radiation"],
        "et0Mm": h["et0_fao_evapotranspiration"],
        "precipMm": h["precipitation"],
    })
    if len(df) < 24 * days:
        raise ValueError(f"Réponse incomplète : {len(df)} heures")
    return df.fillna(0.0)


def _read_valid_cache(lat: float, lon: float, days: int) -> pd.DataFrame | None:
    """Cache utilisable seulement s'il commence aujourd'hui et couvre toute la période demandée."""
    cache = _cache_file(lat, lon)
    if not cache.exists():
        return None
    try:
        df = pd.read_csv(cache).fillna(0.0)
    except Exception:
        return None
    if list(df.columns) != COLUMNS or len(df) < 24 * days or not str(df["hour"].iloc[0]).startswith(_today_str()):
        return None
    return df.head(24 * days).reset_index(drop=True)


def get_weather(lat: float, lon: float, days: int = 2) -> pd.DataFrame:
    """Point d'entrée unique utilisé par /forecast. Lève WeatherUnavailable si aucune donnée réelle n'est disponible."""
    CACHE_DIR.mkdir(exist_ok=True)
    try:
        df = fetch_open_meteo(lat, lon, days)
        df.to_csv(_cache_file(lat, lon), index=False)
        df.attrs["source"] = "api"
        return df
    except Exception as e:  # pas d'internet, timeout, réponse invalide...
        reason = type(e).__name__
        print(f"[weather] Open-Meteo indisponible ({reason}) pour {lat:.4f}, {lon:.4f}")

    df = _read_valid_cache(lat, lon, days)
    if df is not None:
        df.attrs["source"] = "cache"
        return df
    raise WeatherUnavailable(
        f"Prévisions météo indisponibles pour la position {lat:.4f}, {lon:.4f} : "
        f"le service Open-Meteo ne répond pas ({reason}) et aucune prévision récente n'est en cache. "
        "Vérifiez la connexion internet du service Python puis réessayez.")


if __name__ == "__main__":
    # Test rapide (internet requis) : python weather.py
    df = get_weather(33.92, 8.13, days=2)
    print(f"source={df.attrs['source']} | {len(df)} h | Tmax {df['tempC'].max()} °C | "
          f"Irr max {df['irradianceWm2'].max()} W/m2 | ET0 j1 {df['et0Mm'].head(24).sum():.1f} mm")
