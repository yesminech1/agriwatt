"""AgriWatt - Etape 8 : détection d'anomalies de la pompe par IA (Isolation Forest).

1. On ne garde que les périodes où la pompe tourne (powerKw > 0.3) : l'arrêt n'est pas une anomalie.
2. Features : puissance, écart relatif à la médiane, moyenne glissante (1 h), heure de la journée.
3. Isolation Forest (scikit-learn) apprend ce qui est "normal" et isole les points rares.
   score = decision_function : négatif = anormal, plus c'est bas plus c'est suspect.
4. On garde comme anomalie un point isolé par le modèle ET qui s'écarte d'au moins 12 %
   de la médiane (évite les fausses alertes dues au simple bruit de mesure).
5. Une raison lisible en français est générée en comparant à la médiane des périodes ON.
Si trop peu de points (< 20 en marche), on bascule sur les seules règles d'écart.
"""
from typing import List, Tuple

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest

ON_THRESHOLD_KW = 0.3
MIN_POINTS_FOR_MODEL = 20
MIN_DEVIATION = 0.12


def _reason(power: float, median: float) -> str:
    dev = (power - median) / median
    if power < 0.4 * median:
        return f"Puissance très basse ({power:.1f} kW contre {median:.1f} kW) : marche à vide probable"
    if dev > 0:
        return f"Consommation +{dev*100:.0f} % par rapport à la normale (usure probable)"
    return f"Consommation {dev*100:.0f} % par rapport à la normale (fuite possible)"


def detect_anomalies(points: List[Tuple[str, float]]) -> List[dict]:
    """points = [(timestamp, powerKw), ...] -> liste de dicts au format du contrat."""
    df = pd.DataFrame(points, columns=["timestamp", "powerKw"])
    df["on"] = df["powerKw"] > ON_THRESHOLD_KW
    df["isAnomaly"] = False
    df["score"] = 0.0
    df["reason"] = "Pompe à l'arrêt"

    on = df[df["on"]].copy()
    if not on.empty:
        median = float(on["powerKw"].median())
        on["dev"] = (on["powerKw"] - median) / median
        on["rolling"] = on["powerKw"].rolling(4, min_periods=1, center=True).mean()
        on["hour"] = pd.to_datetime(on["timestamp"], errors="coerce").dt.hour.fillna(12)

        if len(on) >= MIN_POINTS_FOR_MODEL:
            X = on[["powerKw", "dev", "rolling", "hour"]].to_numpy()
            model = IsolationForest(n_estimators=200, contamination="auto", random_state=42)
            model.fit(X)
            on["score"] = model.decision_function(X)
            flagged = (model.predict(X) == -1) & (on["dev"].abs() >= MIN_DEVIATION)
        else:  # trop peu de données : règles simples
            on["score"] = 0.1 - on["dev"].abs() / 2
            flagged = (on["dev"] > 0.25) | (on["dev"] < -0.2)

        on["isAnomaly"] = flagged
        on["reason"] = [
            _reason(p, median) if a else "Fonctionnement normal"
            for p, a in zip(on["powerKw"], on["isAnomaly"])
        ]
        df.loc[on.index, ["isAnomaly", "score", "reason"]] = on[["isAnomaly", "score", "reason"]]

    return [
        {"timestamp": r.timestamp, "powerKw": float(r.powerKw), "isAnomaly": bool(r.isAnomaly),
         "score": round(float(r.score), 3), "reason": r.reason}
        for r in df.itertuples(index=False)
    ]
