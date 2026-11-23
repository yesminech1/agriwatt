"""Modèle de détection du stress hydrique (olivier) : définition partagée par l'entraînement et le service.

Un seul endroit décrit l'architecture, les classes et le prétraitement, pour que l'image analysée par l'API
subisse exactement le même traitement que les images de test lors de l'entraînement.
"""
from __future__ import annotations

import io
import json
from pathlib import Path

CLASSES = ["SANS_STRESS", "STRESS_HYDRIQUE"]          # index 0 / 1 (1 = classe positive pour les métriques)
CLASS_LABELS = {"SANS_STRESS": "Aucun stress hydrique détecté",
                "STRESS_HYDRIQUE": "Signes compatibles avec un stress hydrique",
                "INCERTAIN": "Résultat incertain"}
PLANTS = [{"code": "olivier", "label": "Olivier"}]    # seules espèces présentes dans le dataset
IMG_SIZE = 224
RESIZE = 256
MEAN = [0.485, 0.456, 0.406]                          # normalisation ImageNet (modèle pré-entraîné)
STD = [0.229, 0.224, 0.225]
MODEL_FILE = "stress_model.pt"
META_FILE = "stress_model.json"
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MIN_SIDE = 64
ARCHS = ("large", "small")


def build_model(arch: str = "large", pretrained: bool = True):
    """MobileNetV3 (Transfer Learning) avec une tête à 2 classes."""
    import torch.nn as nn
    from torchvision import models

    if arch not in ARCHS:
        raise ValueError(f"Architecture inconnue : {arch}")
    if arch == "large":
        weights = models.MobileNet_V3_Large_Weights.IMAGENET1K_V2 if pretrained else None
        model = models.mobilenet_v3_large(weights=weights)
    else:
        weights = models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None
        model = models.mobilenet_v3_small(weights=weights)
    in_features = model.classifier[-1].in_features
    model.classifier[-1] = nn.Linear(in_features, len(CLASSES))
    return model


def eval_transform():
    from torchvision import transforms
    return transforms.Compose([
        transforms.Resize(RESIZE),
        transforms.CenterCrop(IMG_SIZE),
        transforms.ToTensor(),
        transforms.Normalize(MEAN, STD),
    ])


def train_transform():
    """Augmentation raisonnable : cadrage, symétrie, légère rotation et lumière.
    Pas de changement de teinte ni de saturation : la couleur du feuillage est justement un signe de stress."""
    from torchvision import transforms
    return transforms.Compose([
        transforms.RandomResizedCrop(IMG_SIZE, scale=(0.7, 1.0), ratio=(0.8, 1.25)),
        transforms.RandomHorizontalFlip(),
        transforms.RandomRotation(10),
        transforms.ColorJitter(brightness=0.15, contrast=0.15),
        transforms.ToTensor(),
        transforms.Normalize(MEAN, STD),
    ])


class InvalidImage(ValueError):
    """Fichier refusé (format, taille, contenu illisible)."""


def load_image_bytes(data: bytes):
    """Valide et décode une image JPEG/PNG. Renvoie une image PIL RGB orientée selon l'EXIF."""
    from PIL import Image, ImageOps, UnidentifiedImageError

    if not data:
        raise InvalidImage("Le fichier est vide.")
    if len(data) > MAX_IMAGE_BYTES:
        raise InvalidImage(f"Image trop volumineuse (maximum {MAX_IMAGE_BYTES // (1024 * 1024)} Mo).")
    try:
        with Image.open(io.BytesIO(data)) as probe:
            fmt = probe.format
            probe.verify()                       # détecte les fichiers tronqués ou corrompus
        if fmt not in ("JPEG", "PNG"):
            raise InvalidImage("Format non accepté : seules les images JPG, JPEG et PNG sont acceptées.")
        img = Image.open(io.BytesIO(data))
        img.load()
    except InvalidImage:
        raise
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError):
        raise InvalidImage("Le fichier n'est pas une image valide ou il est endommagé.")
    img = ImageOps.exif_transpose(img).convert("RGB")      # photos de téléphone : orientation réelle
    if min(img.size) < MIN_SIDE:
        raise InvalidImage(f"Image trop petite (au moins {MIN_SIDE} × {MIN_SIDE} pixels).")
    return img


def save_model(model, arch: str, out_dir: Path, metadata: dict) -> None:
    import torch
    out_dir.mkdir(parents=True, exist_ok=True)
    torch.save({"arch": arch, "classes": CLASSES, "state_dict": model.state_dict()}, out_dir / MODEL_FILE)
    (out_dir / META_FILE).write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def load_model(model_dir: Path):
    """Charge le modèle sauvegardé (sans réentraînement). Renvoie (modèle en mode eval, métadonnées)."""
    import torch
    ckpt = torch.load(model_dir / MODEL_FILE, map_location="cpu", weights_only=True)
    if ckpt.get("classes") != CLASSES:
        raise ValueError("Le modèle sauvegardé ne correspond pas aux classes attendues.")
    model = build_model(ckpt["arch"], pretrained=False)
    model.load_state_dict(ckpt["state_dict"])
    model.eval()
    meta = json.loads((model_dir / META_FILE).read_text(encoding="utf-8"))
    return model, meta
