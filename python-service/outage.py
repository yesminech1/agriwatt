"""AgriWatt - Etape 5 : risque de coupure du réseau (score 0 à 1).

ATTENTION : c'est un PROXY, pas une donnée réelle de la STEG.
Hypothèse : le réseau sature quand il fait très chaud (climatisation)
et aux heures de pointe de consommation.
  score = 0.10 (base)
        + 0.40 si heure de pointe (13h-17h ou 19h-22h)
        + jusqu'à 0.45 selon la température au-dessus de 38 °C (max atteint à 46 °C)
Option démo : forceOutageStart / forceOutageEnd -> score = 1.0 dans cette plage.
"""
from typing import Optional

BASE = 0.10
PEAK_BONUS = 0.40
HEAT_MAX_BONUS = 0.45
HEAT_START_C = 38.0
HEAT_FULL_C = 46.0


def is_peak_hour(hour: int) -> bool:
    return 13 <= hour < 17 or 19 <= hour < 22


def outage_risk(temp_c: float, hour: int,
                force_start: Optional[int] = None, force_end: Optional[int] = None) -> float:
    if force_start is not None and force_end is not None and force_start <= hour < force_end:
        return 1.0
    heat = min(1.0, max(0.0, (temp_c - HEAT_START_C) / (HEAT_FULL_C - HEAT_START_C)))
    score = BASE + (PEAK_BONUS if is_peak_hour(hour) else 0.0) + HEAT_MAX_BONUS * heat
    return round(min(1.0, score), 2)