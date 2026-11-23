"""Tests du module de détection du stress hydrique.

- Préparation des données : sur le VRAI Growth.xlsx (étiquettes, découpage par arbre, absence de fuite).
- Pipeline d'entraînement : exécuté de bout en bout sur de petites images SYNTHÉTIQUES générées ici
  (les photos Mendeley font 9,6 Go). Ces images et le modèle obtenu n'existent que dans les tests :
  ils servent à vérifier le code, pas à mesurer une performance.
- API : chargement unique du modèle, validation des fichiers, réponse = sortie réelle du réseau.
"""
import io
import sys
from pathlib import Path

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

SERVICE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVICE / "ml"))

import dataset as ds  # noqa: E402
import detection  # noqa: E402
import main  # noqa: E402
import stress_model as sm  # noqa: E402

torch = pytest.importorskip("torch")
EXCEL = SERVICE / "ml" / "data" / "Growth.xlsx"


# ---------- Données réelles (Growth.xlsx) ----------
def test_labels_come_only_from_100_and_0_percent_regimes_after_start_date():
    df = ds.read_growth(EXCEL)
    samples = ds.build_samples(df)
    assert {s.regime for s in samples} == {"100%", "0%"}
    assert all((s.label == 0) == (s.regime == "100%") for s in samples)
    assert min(s.date for s in samples) >= ds.DEFAULT_START_DATE
    assert all(s.image_id.rsplit("_", 1)[1] == str(s.row) for s in samples)   # identifiant = numéro de ligne
    assert {s.view for s in samples} == {"top", "side"}
    assert len({s.tree for s in samples}) == 40                              # 20 arbres à 100 %, 20 à 0 %


def test_split_is_by_tree_without_leakage_and_balanced():
    samples = ds.build_samples(ds.read_growth(EXCEL))
    ds.split_trees(samples, seed=42)
    trees = {split: {s.tree for s in samples if s.split == split} for split in ("train", "val", "test")}
    assert not (trees["train"] & trees["val"]) and not (trees["train"] & trees["test"]) and not (trees["val"] & trees["test"])
    assert [len(trees[k]) for k in ("train", "val", "test")] == [28, 6, 6]           # 70 / 15 / 15 %
    for split in ("val", "test"):
        part = [s for s in samples if s.split == split]
        assert sum(s.label for s in part) * 2 == len(part)                            # classes équilibrées
        assert {s.variety for s in part} == {"Languedoc", "Haouzia", "Menara"}


# ---------- Pipeline d'entraînement (images synthétiques de test) ----------
def _fake_photo(label: int, seed: int) -> Image.Image:
    rng = np.random.default_rng(seed)
    base = np.array([60, 140, 60]) if label == 0 else np.array([150, 120, 60])
    arr = np.clip(base + rng.normal(0, 25, (96, 96, 3)), 0, 255).astype("uint8")
    return Image.fromarray(arr)


@pytest.fixture(scope="module")
def trained_dir(tmp_path_factory):
    """Images rangées comme les téléchargements Mendeley, SANS décompression :
    vue de dessus dans un .zip, vue de côté dans un .zip lui-même rangé dans un .zip."""
    import zipfile
    root = tmp_path_factory.mktemp("detection")
    samples = ds.build_samples(ds.read_growth(EXCEL))
    bad = samples[0]                                   # un identifiant attendu mais fichier corrompu : écarté, pas de plantage

    def jpg(s):
        if s is bad:
            return b"\xff\xd8\xff corrompu"
        buf = io.BytesIO(); _fake_photo(s.label, s.row * 2 + (s.view == "side")).save(buf, "JPEG", quality=90)
        return buf.getvalue()

    top = root / "Top view images.zip"
    with zipfile.ZipFile(top, "w") as z:
        for s in samples:
            if s.view == "top":
                z.writestr(f"Top view/{s.image_id}.jpg", jpg(s))
        z.writestr("__MACOSX/Top view/._drought2_999.jpg", b"parasite")
        z.writestr("Top view/notes.png", b"pas une image")
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as z:
        for s in samples:
            if s.view == "side":
                z.writestr(f"side/{s.image_id}.JPG", jpg(s))
    side = root / "Side view images.zip"
    with zipfile.ZipFile(side, "w", compression=zipfile.ZIP_STORED) as z:
        z.writestr("Side view images.zip", inner.getvalue())
    import train
    meta = train.main(["--images", str(top), str(side), "--out", str(root / "models"), "--cache", str(root / "cache"),
                       "--arch", "small", "--no-pretrained", "--limit", "160", "--epochs-head", "1",
                       "--epochs-finetune", "1", "--batch-size", "16"])
    return root / "models", meta, bad.image_id, root


def test_images_read_from_archives_without_extraction(trained_dir):
    _, meta, _, root = trained_dir
    assert meta["dataset"]["missing"] == 0                                    # 1 040 images trouvées dans les .zip
    assert not [p for p in root.rglob("*") if p.suffix.lower() == ".jpg" and "cache" not in p.parts]
    cached = list((root / "cache").rglob("*.jpg"))
    assert cached and all(p.stat().st_size < 100_000 for p in cached)         # seules de petites copies sont écrites


def test_training_pipeline_saves_model_and_honest_metadata(trained_dir):
    model_dir, meta, bad_id, _ = trained_dir
    assert (model_dir / sm.MODEL_FILE).exists() and (model_dir / sm.META_FILE).exists()
    assert meta["testOnly"] is True                                           # jamais présenté comme un vrai modèle
    assert any(i["image"] == bad_id for i in meta["dataset"]["invalid"])
    split = meta["dataset"]["split"]
    assert not set(split["train"]["arbres"]) & set(split["test"]["arbres"])
    t = meta["metrics"]["test"]
    for k in ("accuracy", "precision", "recall", "f1"):
        assert 0.0 <= t[k] <= 1.0
    cm = t["confusion_matrix"]["rows_true_cols_pred"]
    assert sum(map(sum, cm)) == t["images"] == split["test"]["images"]
    assert meta["limitations"]


# ---------- API ----------
@pytest.fixture()
def client(trained_dir, monkeypatch):
    det = detection.StressDetector(trained_dir[0])
    monkeypatch.setattr(main, "detector", det)
    return TestClient(main.app), det


def _jpeg(label=0, size=(120, 90), fmt="JPEG"):
    buf = io.BytesIO()
    _fake_photo(label, 7).resize(size).save(buf, fmt)
    return buf.getvalue()


def test_analyze_returns_the_real_model_output_and_loads_model_once(client):
    c, det = client
    data = _jpeg()
    responses = [c.post("/detection/analyze", files={"file": ("olivier.jpg", data, "image/jpeg")},
                        data={"plantType": "olivier"}) for _ in range(3)]
    assert all(r.status_code == 200 for r in responses)
    body = responses[0].json()
    assert det.load_count == 1                                                # pas de rechargement ni de réentraînement
    # même calcul fait directement avec le modèle sauvegardé
    model, meta = sm.load_model(det.model_dir)
    x = sm.eval_transform()(sm.load_image_bytes(data)).unsqueeze(0)
    with torch.no_grad():
        p = torch.softmax(model(x), 1)[0].tolist()
    assert body["probabilities"]["STRESS_HYDRIQUE"] == pytest.approx(p[1], abs=1e-4)
    assert body["confidence"] == pytest.approx(max(p), abs=1e-4)
    expected = sm.CLASSES[int(p[1] > p[0])] if max(p) >= meta["uncertainThreshold"] else "INCERTAIN"
    assert body["prediction"] == expected
    assert body["modelClass"] in sm.CLASSES and body["plantLabel"] == "Olivier"
    assert body["recommendation"] and body["interpretation"]


def test_png_accepted_and_invalid_files_rejected(client):
    c, _ = client
    ok = c.post("/detection/analyze", files={"file": ("a.png", _jpeg(fmt="PNG"), "image/png")}, data={"plantType": "olivier"})
    assert ok.status_code == 200
    text = c.post("/detection/analyze", files={"file": ("a.jpg", b"bonjour", "image/jpeg")}, data={"plantType": "olivier"})
    assert text.status_code == 400 and "image" in text.json()["detail"]
    gif = io.BytesIO(); Image.new("RGB", (100, 100)).save(gif, "GIF")
    r = c.post("/detection/analyze", files={"file": ("a.gif", gif.getvalue(), "image/gif")}, data={"plantType": "olivier"})
    assert r.status_code == 400 and "JPG" in r.json()["detail"]
    tiny = c.post("/detection/analyze", files={"file": ("a.jpg", _jpeg(size=(20, 20)), "image/jpeg")}, data={"plantType": "olivier"})
    assert tiny.status_code == 400
    big = c.post("/detection/analyze", files={"file": ("a.jpg", b"\xff" * (sm.MAX_IMAGE_BYTES + 10), "image/jpeg")},
                 data={"plantType": "olivier"})
    assert big.status_code == 413


def test_unsupported_plant_rejected(client):
    c, _ = client
    r = c.post("/detection/analyze", files={"file": ("a.jpg", _jpeg(), "image/jpeg")}, data={"plantType": "tomate"})
    assert r.status_code == 400 and "Olivier" in r.json()["detail"]


def test_model_info_exposes_measured_metrics(client):
    c, _ = client
    info = c.get("/detection/model").json()
    assert info["available"] is True and info["plants"] == [{"code": "olivier", "label": "Olivier"}]
    assert set(info["metrics"]["test"]) >= {"accuracy", "precision", "recall", "f1", "confusion_matrix"}


def test_missing_model_gives_503_and_explanation(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "detector", detection.StressDetector(tmp_path / "vide"))
    c = TestClient(main.app)
    info = c.get("/detection/model").json()
    assert info["available"] is False and "train.py" in info["message"]
    r = c.post("/detection/analyze", files={"file": ("a.jpg", _jpeg(), "image/jpeg")}, data={"plantType": "olivier"})
    assert r.status_code == 503 and "train.py" in r.json()["detail"]
