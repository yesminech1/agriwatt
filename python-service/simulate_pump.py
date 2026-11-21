"""AgriWatt - Etape 7 : données de pompe simulées (pas de 15 min).

Pompe nominale 5 kW, en marche de 8h à 17h (+ bruit de mesure), arrêtée sinon.
Anomalies injectées (vérité terrain dans la colonne "injected") :
  - usure        : +30 à +40 % de puissance (roulements, frottements)
  - fuite        : -20 à -30 % (la pompe travaille contre une pression plus faible)
  - marche à vide: ~25 % de la puissance (pompe désamorcée, plus d'eau aspirée)
"""
from datetime import datetime, timedelta

import numpy as np
import pandas as pd

NOMINAL_KW = 5.0
STEP_MIN = 15


def simulate_pump(days: int = 7, seed: int = 42, end: datetime | None = None) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    if end is None:
        end = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start = end - timedelta(days=days)
    n = days * 24 * 60 // STEP_MIN
    ts = [start + timedelta(minutes=STEP_MIN * i) for i in range(n)]
    on = np.array([8 <= t.hour < 17 for t in ts])
    power = np.where(on, NOMINAL_KW + rng.normal(0, 0.12, n), 0.0)
    injected = np.array([""] * n, dtype=object)

    def inject(day: int, hour: float, duration_h: float, kind: str):
        """Applique une anomalie sur un épisode (jour, heure de début, durée)."""
        for i, t in enumerate(ts):
            h = (t - start).total_seconds() / 3600 - day * 24
            if hour <= h < hour + duration_h and on[i]:
                if kind == "usure":
                    power[i] = NOMINAL_KW * rng.uniform(1.30, 1.40)
                elif kind == "fuite":
                    power[i] = NOMINAL_KW * rng.uniform(0.70, 0.80)
                elif kind == "vide":
                    power[i] = NOMINAL_KW * rng.uniform(0.20, 0.30)
                injected[i] = kind

    inject(day=1, hour=10, duration_h=1.5, kind="usure")
    inject(day=3, hour=14, duration_h=1.0, kind="vide")
    if days > 5:
        inject(day=5, hour=9, duration_h=2.0, kind="fuite")

    return pd.DataFrame({
        "timestamp": [t.strftime("%Y-%m-%dT%H:%M") for t in ts],
        "powerKw": np.round(power, 2),
        "injected": injected,
    })


if __name__ == "__main__":
    df = simulate_pump()
    print(df.head(3).to_string(index=False))
    print(f"{len(df)} points | anomalies injectées : {df['injected'].value_counts().to_dict()}")