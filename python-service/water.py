"""AgriWatt - Etape 4 : besoin en eau des cultures.

Besoin (m3) = ET0 (mm) x Kc x surface (m2) / 1000 / 0.9
- Kc : coefficient cultural (hypothèses, à vérifier dans FAO-56)
- 0.9 : efficience du goutte-à-goutte (10 % de pertes)
On applique la formule à l'ET0 HORAIRE : le besoin est donc réparti
proportionnellement à l'évaporation de chaque heure (fort à midi, nul la nuit).
"""

KC = {"olive": 0.65, "datte": 0.95, "tomate": 1.05, "ble": 1.15}
IRRIGATION_EFFICIENCY = 0.9


def water_need_m3(et0_mm: float, crop: str, area_m2: float) -> float:
    """Besoin en eau (m3) pour une période dont l'ET0 vaut et0_mm."""
    return max(0.0, et0_mm) * KC[crop] * area_m2 / 1000 / IRRIGATION_EFFICIENCY