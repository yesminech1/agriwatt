"""AgriWatt - Etape 3 : production solaire.

pvKw = irradianceWm2 x panelAreaM2 x efficiency x 0.8 / 1000
Le facteur 0.8 regroupe les pertes (chaleur, onduleur, poussière, câbles).
"""


def pv_kw(irradiance_wm2: float, panel_area_m2: float, efficiency: float = 0.18) -> float:
    """Puissance solaire produite (kW) pour une irradiance donnée."""
    return max(0.0, irradiance_wm2) * panel_area_m2 * efficiency * 0.8 / 1000
