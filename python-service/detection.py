"""Inférence du modèle de stress hydrique : chargé UNE fois (au premier appel), jamais réentraîné ici.

Le dossier du modèle est models/ (ou la variable d'environnement AGRIWATT_MODEL_DIR).
PyTorch est importé seulement à ce moment-là : sans PyTorch ni modèle, le reste du service fonctionne
et la détection répond 503 avec une explication.
"""
from __future__ import annotations

import os
import threading
from pathlib import Path

import stress_model as sm

DEFAULT_MODEL_DIR = Path(__file__).resolve().parent / "models"

INTERPRETATIONS = {
    "SANS_STRESS": "Les signes visuels de l'image (couleur et densité du feuillage, port des rameaux) ressemblent à ceux "
                   "des oliviers correctement irrigués du jeu d'entraînement.",
    "STRESS_HYDRIQUE": "Les signes visuels de l'image ressemblent à ceux des oliviers non irrigués du jeu d'entraînement "
                       "(feuillage moins dense ou plus terne, rameaux affaissés).",
    "INCERTAIN": "Le modèle n'est pas assez sûr de lui pour trancher : l'image ne ressemble clairement à aucune des deux "
                 "classes apprises (cadrage, lumière, autre stade ou autre espèce possible).",
}
RECOMMENDATIONS = {
    "SANS_STRESS": "Aucune action urgente. Continuez le suivi habituel et vérifiez ponctuellement l'humidité du sol.",
    "STRESS_HYDRIQUE": "Vérifiez l'humidité du sol au pied de l'arbre et le bon fonctionnement du système d'irrigation "
                       "(goutteurs, pression, horaires). Confirmez par un contrôle sur place avant de modifier l'irrigation.",
    "INCERTAIN": "Reprenez une photo nette de l'arbre entier, en plein jour, puis relancez l'analyse. En attendant, "
                 "contrôlez l'humidité du sol avant toute décision d'irrigation.",
}


class ModelUnavailable(RuntimeError):
    """Modèle absent ou impossible à charger (message lisible par l'utilisateur)."""


class StressDetector:
    def __init__(self, model_dir: Path | None = None):
        self.model_dir = Path(model_dir or os.environ.get("AGRIWATT_MODEL_DIR") or DEFAULT_MODEL_DIR)
        self._lock = threading.Lock()
        self._model = None
        self._meta = None
        self._transform = None
        self.load_count = 0

    def _ensure_loaded(self):
        if self._model is not None:
            return
        with self._lock:
            if self._model is not None:
                return
            if not (self.model_dir / sm.MODEL_FILE).exists() or not (self.model_dir / sm.META_FILE).exists():
                raise ModelUnavailable("Le modèle de détection n'est pas encore entraîné : lancez "
                                       "« python ml/train.py --images-dir <dossier des images> » dans python-service.")
            try:
                import torch  # noqa: F401
            except ImportError:
                raise ModelUnavailable("PyTorch n'est pas installé : lancez « pip install -r requirements.txt ».")
            try:
                model, meta = sm.load_model(self.model_dir)
            except Exception as e:  # noqa: BLE001
                raise ModelUnavailable(f"Impossible de charger le modèle ({type(e).__name__}). Relancez l'entraînement.")
            self._transform = sm.eval_transform()
            self._meta = meta
            self._model = model
            self.load_count += 1
            print(f"[détection] modèle {meta.get('arch')} v{meta.get('version')} chargé depuis {self.model_dir}")

    def info(self) -> dict:
        try:
            self._ensure_loaded()
        except ModelUnavailable as e:
            return {"available": False, "message": str(e), "plants": sm.PLANTS}
        m = self._meta
        return {
            "available": True,
            "version": m.get("version"), "trainedAt": m.get("trainedAt"), "arch": m.get("arch"),
            "testOnly": m.get("testOnly", False),
            "classes": m.get("classes"), "plants": m.get("plants", sm.PLANTS),
            "uncertainThreshold": m.get("uncertainThreshold"),
            "labelDefinition": m.get("labelDefinition"),
            "metrics": m.get("metrics", {}),
            "dataset": {k: v for k, v in (m.get("dataset") or {}).items() if k not in ("invalid", "duplicatesRemoved")},
            "limitations": m.get("limitations", []),
        }

    def analyze(self, image_bytes: bytes, plant_type: str) -> dict:
        """Valide l'image, applique le prétraitement d'entraînement et renvoie la vraie sortie du modèle."""
        plants = {p["code"]: p["label"] for p in sm.PLANTS}
        if plant_type not in plants:
            raise sm.InvalidImage(f"Plante non prise en charge par le modèle : seules {', '.join(plants.values())} sont reconnues.")
        img = sm.load_image_bytes(image_bytes)          # InvalidImage si le fichier est refusé
        self._ensure_loaded()
        import torch
        x = self._transform(img).unsqueeze(0)
        with torch.no_grad():
            probs = torch.softmax(self._model(x), dim=1)[0].tolist()
        best = max(range(len(probs)), key=lambda i: probs[i])
        model_class = sm.CLASSES[best]
        confidence = float(probs[best])
        threshold = float(self._meta.get("uncertainThreshold", 0.65))
        prediction = model_class if confidence >= threshold else "INCERTAIN"
        return {
            "success": True,
            "plantType": plant_type,
            "plantLabel": plants[plant_type],
            "prediction": prediction,
            "predictionLabel": sm.CLASS_LABELS[prediction],
            "modelClass": model_class,
            "confidence": round(confidence, 4),
            "probabilities": {c: round(float(p), 4) for c, p in zip(sm.CLASSES, probs)},
            "uncertainThreshold": threshold,
            "interpretation": INTERPRETATIONS[prediction],
            "recommendation": RECOMMENDATIONS[prediction],
            "modelVersion": self._meta.get("version"),
            "imageWidth": img.size[0],
            "imageHeight": img.size[1],
        }


detector = StressDetector()
