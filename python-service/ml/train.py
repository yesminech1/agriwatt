"""Entraînement reproductible du modèle de stress hydrique (olivier) — à lancer depuis python-service/ :

    python ml/train.py --images "C:\\Users\\moi\\Downloads\\archive1.zip" "C:\\Users\\moi\\Downloads\\archive2.zip"

--images accepte des archives .zip (lues directement, SANS décompression) et/ou des dossiers.

Étapes : association images ↔ Growth.xlsx, contrôle des fichiers, suppression des doublons, découpage par arbre
70/15/15, prétraitement (cache redimensionné), Transfer Learning MobileNetV3 (tête puis fin des couches),
sélection sur la validation, évaluation UNE fois sur le test, sauvegarde dans models/.
Le service FastAPI recharge ensuite models/stress_model.pt sans jamais réentraîner.
"""
from __future__ import annotations

import argparse
import copy
import json
import random
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVICE = HERE.parent
sys.path.insert(0, str(SERVICE))
sys.path.insert(0, str(HERE))

import numpy as np  # noqa: E402

import dataset as ds  # noqa: E402
import stress_model as sm  # noqa: E402


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Entraîne le modèle de détection du stress hydrique de l'olivier.")
    p.add_argument("--images", "--images-dir", dest="images", required=True, type=Path, nargs="+",
                   help="Archives .zip de Mendeley (lues sans décompression) et/ou dossiers d'images.")
    p.add_argument("--excel", type=Path, default=HERE / "data" / "Growth.xlsx")
    p.add_argument("--out", type=Path, default=SERVICE / "models")
    p.add_argument("--cache", type=Path, default=HERE / "cache")
    p.add_argument("--start-date", default=ds.DEFAULT_START_DATE, help="Première date gardée (AAAA-MM-JJ).")
    p.add_argument("--views", default="top,side", help="Vues utilisées : top, side ou top,side.")
    p.add_argument("--arch", choices=sm.ARCHS, default="large")
    p.add_argument("--epochs-head", type=int, default=6, help="Époques avec le réseau pré-entraîné figé.")
    p.add_argument("--epochs-finetune", type=int, default=10, help="Époques en dégelant les derniers blocs.")
    p.add_argument("--patience", type=int, default=4)
    p.add_argument("--batch-size", type=int, default=32)
    p.add_argument("--lr-head", type=float, default=1e-3)
    p.add_argument("--lr-finetune", type=float, default=1e-4)
    p.add_argument("--unfreeze-blocks", type=int, default=4)
    p.add_argument("--uncertain-threshold", type=float, default=0.65,
                   help="En dessous de ce score, le résultat est présenté comme incertain.")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--workers", type=int, default=0, help="0 recommandé sous Windows.")
    p.add_argument("--no-pretrained", action="store_true", help="Tests automatiques hors ligne uniquement.")
    p.add_argument("--limit", type=int, default=0, help="Tests automatiques : n'utilise que N images.")
    return p.parse_args(argv)


def set_seed(seed: int):
    import torch
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def check_image(data: bytes):
    """Renvoie None si l'image est exploitable, sinon la raison du rejet."""
    import io
    from PIL import Image
    try:
        with Image.open(io.BytesIO(data)) as im:
            fmt = im.format
            im.verify()
        if fmt not in ("JPEG", "PNG"):
            return f"format {fmt} non accepté"
        with Image.open(io.BytesIO(data)) as im:
            im.load()
            if min(im.size) < sm.MIN_SIDE:
                return "image trop petite"
    except Exception as e:  # noqa: BLE001
        return f"illisible ({type(e).__name__})"
    return None


def cache_image(data: bytes, dst: Path):
    """Redimensionne une fois pour toutes (côté court = 320 px, environ 30 Ko) : les époques suivantes lisent de petits fichiers."""
    import io
    from PIL import Image, ImageOps
    dst.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(io.BytesIO(data)) as im:
        im = ImageOps.exif_transpose(im).convert("RGB")
        w, h = im.size
        scale = 320 / min(w, h)
        if scale < 1:
            im = im.resize((round(w * scale), round(h * scale)), Image.Resampling.LANCZOS)
        im.save(dst, "JPEG", quality=92)


class ImageSet:
    def __init__(self, items, transform):
        self.items, self.transform = items, transform

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        from PIL import Image
        path, label = self.items[i]
        with Image.open(path) as im:
            x = self.transform(im.convert("RGB"))
        return x, label


def run_epoch(model, loader, criterion, optimizer=None):
    import torch
    training = optimizer is not None
    model.train(training)
    if training:                    # couches figées : leurs statistiques BatchNorm restent celles d'ImageNet
        for m in model.modules():
            if isinstance(m, torch.nn.BatchNorm2d) and not any(p.requires_grad for p in m.parameters()):
                m.eval()
    total, loss_sum, probs, labels = 0, 0.0, [], []
    with torch.set_grad_enabled(training):
        for x, y in loader:
            out = model(x)
            loss = criterion(out, y)
            if training:
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
            loss_sum += loss.item() * len(y)
            total += len(y)
            probs.append(torch.softmax(out.detach(), 1)[:, 1].numpy())
            labels.append(y.numpy())
    return loss_sum / max(total, 1), np.concatenate(probs), np.concatenate(labels)


def metrics(y_true, p_stress, threshold):
    from sklearn.metrics import accuracy_score, confusion_matrix, f1_score, precision_score, recall_score
    y_pred = (p_stress >= 0.5).astype(int)
    conf = np.maximum(p_stress, 1 - p_stress)
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1])
    return {
        "images": int(len(y_true)),
        "accuracy": round(float(accuracy_score(y_true, y_pred)), 4),
        "precision": round(float(precision_score(y_true, y_pred, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, y_pred, zero_division=0)), 4),
        "f1": round(float(f1_score(y_true, y_pred, zero_division=0)), 4),
        "confusion_matrix": {"labels": sm.CLASSES, "rows_true_cols_pred": cm.tolist()},
        "uncertain_rate": round(float((conf < threshold).mean()), 4) if len(conf) else 0.0,
    }


def set_trainable(model, unfreeze_blocks: int):
    for p in model.parameters():
        p.requires_grad = False
    for p in model.classifier.parameters():
        p.requires_grad = True
    if unfreeze_blocks > 0:
        for block in list(model.features)[-unfreeze_blocks:]:
            for p in block.parameters():
                p.requires_grad = True


def main(argv=None) -> dict:
    args = parse_args(argv)
    import torch
    from torch.utils.data import DataLoader

    set_seed(args.seed)
    t0 = time.time()
    views = tuple(v.strip() for v in args.views.split(",") if v.strip())
    for src in args.images:
        if not (src.is_dir() or (src.is_file() and src.suffix.lower() == ".zip")):
            sys.exit(f"Introuvable (dossier ou archive .zip attendu) : {src}")
    reader = ds.ImageReader()

    # ---- 1. Données ----
    print("1/5 Lecture de", args.excel)
    df = ds.read_growth(args.excel)
    report = ds.DataReport(rows=len(df))
    samples = ds.build_samples(df, start_date=args.start_date, views=views)
    report.rows_selected = len({s.row for s in samples})
    report.referenced_images = len(samples)
    print(f"   {report.rows_selected} séances retenues (100 % vs 0 %, depuis {args.start_date}) -> {len(samples)} images attendues")
    print("2/5 Association et contrôle des images dans", ", ".join(str(x) for x in args.images))
    samples = ds.attach_images(samples, args.images, report, check_image, reader)
    print(f"   trouvées : {report.found}  manquantes : {len(report.missing)}  invalides : {len(report.invalid)}"
          f"  doublons écartés : {len(report.duplicates_removed)}")
    if args.limit:
        rng = random.Random(args.seed)
        rng.shuffle(samples)
        samples = sorted(samples[: args.limit], key=lambda s: (s.row, s.view))
    if len({s.label for s in samples}) < 2 or len({s.tree for s in samples}) < 6:
        sys.exit("Pas assez d'images associées pour entraîner un modèle (vérifiez --images). "
                 f"Exemples d'identifiants attendus : {[s.image_id for s in samples[:3]] or report.missing[:3]}")
    ds.split_trees(samples, seed=args.seed)
    summary = ds.summarize(samples)
    for split, info in summary.items():
        print(f"   {split:5s} : {info['images']:4d} images, {info['trees']:2d} arbres, {info['par_classe']}")

    # ---- 2. Prétraitement ----
    print("3/5 Prétraitement (redimensionnement mis en cache dans", args.cache, ")")
    items = {"train": [], "val": [], "test": []}
    for i, s in enumerate(samples, 1):
        dst = args.cache / s.view / f"{s.image_id}.jpg"
        if not dst.exists():
            cache_image(reader.read(s.path), dst)
        items[s.split].append((dst, s.label))
        if i % 200 == 0:
            print(f"   {i}/{len(samples)}")

    reader.close()
    common = dict(batch_size=args.batch_size, num_workers=args.workers)
    train_loader = DataLoader(ImageSet(items["train"], sm.train_transform()), shuffle=True,
                              generator=torch.Generator().manual_seed(args.seed), **common)
    val_loader = DataLoader(ImageSet(items["val"], sm.eval_transform()), shuffle=False, **common)
    test_loader = DataLoader(ImageSet(items["test"], sm.eval_transform()), shuffle=False, **common)

    # ---- 3. Entraînement ----
    print(f"4/5 Entraînement MobileNetV3-{args.arch} ({'pré-entraîné ImageNet' if not args.no_pretrained else 'SANS pré-entraînement (test)'})")
    model = sm.build_model(args.arch, pretrained=not args.no_pretrained)
    counts = np.bincount([y for _, y in items["train"]], minlength=2).astype(float)
    weights = torch.tensor(counts.sum() / (2 * np.maximum(counts, 1)), dtype=torch.float32)
    criterion = torch.nn.CrossEntropyLoss(weight=weights)

    best = {"loss": float("inf"), "state": copy.deepcopy(model.state_dict()), "epoch": 0, "phase": ""}
    history = []
    epoch_no = 0
    for phase, epochs, lr, blocks in (("tête", args.epochs_head, args.lr_head, 0),
                                       ("affinage", args.epochs_finetune, args.lr_finetune, args.unfreeze_blocks)):
        if epochs <= 0:
            continue
        set_trainable(model, blocks)
        optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=lr, weight_decay=1e-4)
        stale = 0
        for _ in range(epochs):
            epoch_no += 1
            tl, _, _ = run_epoch(model, train_loader, criterion, optimizer)
            vl, vp, vy = run_epoch(model, val_loader, criterion)
            vm = metrics(vy, vp, args.uncertain_threshold)
            history.append({"epoch": epoch_no, "phase": phase, "train_loss": round(tl, 4), "val_loss": round(vl, 4),
                            "val_accuracy": vm["accuracy"], "val_f1": vm["f1"]})
            print(f"   époque {epoch_no:2d} [{phase}] perte entr. {tl:.3f}  perte val. {vl:.3f}"
                  f"  acc. val. {vm['accuracy']:.3f}  F1 val. {vm['f1']:.3f}")
            if vl < best["loss"] - 1e-4:
                best = {"loss": vl, "state": copy.deepcopy(model.state_dict()), "epoch": epoch_no, "phase": phase}
                stale = 0
            else:
                stale += 1
                if stale >= args.patience:
                    print("   arrêt anticipé (la validation ne progresse plus)")
                    break
        model.load_state_dict(best["state"])            # la phase suivante repart du meilleur modèle

    # ---- 4. Évaluation finale (test indépendant, une seule fois) ----
    print("5/5 Évaluation sur le jeu de test (arbres jamais vus)")
    model.load_state_dict(best["state"])
    _, vp, vy = run_epoch(model, val_loader, criterion)
    _, tp, ty = run_epoch(model, test_loader, criterion)
    test_metrics = metrics(ty, tp, args.uncertain_threshold)
    test_views = [s.view for s in samples if s.split == "test"]
    per_view = {}
    for v in views:
        mask = np.array([tv == v for tv in test_views])
        if mask.any():
            per_view[v] = metrics(ty[mask], tp[mask], args.uncertain_threshold)

    limitations = [
        "Modèle entraîné uniquement sur de jeunes oliviers (variétés Languedoc, Haouzia, Menara) photographiés pendant un essai au Maroc (mars-juillet 2024).",
        "Les étiquettes viennent du régime d'irrigation appliqué (100 % vs 0 %), pas d'une mesure de l'état hydrique de chaque arbre.",
        f"Jeu de test : {summary['test']['trees']} arbres seulement ; les métriques ont donc une marge d'incertitude importante.",
        "Le score de confiance est la sortie softmax du réseau : il n'est pas calibré et ne doit pas être lu comme une probabilité.",
        "Le résultat repose sur des signes visuels ; il ne mesure pas l'humidité du sol ni le potentiel hydrique de la plante.",
    ]
    meta = {
        "version": datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S"),
        "trainedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "arch": f"mobilenet_v3_{args.arch}",
        "pretrained": not args.no_pretrained,
        "testOnly": bool(args.no_pretrained or args.limit),
        "classes": sm.CLASSES,
        "plants": sm.PLANTS,
        "imageSize": sm.IMG_SIZE,
        "uncertainThreshold": args.uncertain_threshold,
        "labelDefinition": {"SANS_STRESS": "irrigation 100 % des besoins (groupes A1, A2)",
                            "STRESS_HYDRIQUE": "aucune irrigation, 0 % (groupes D1, D2)",
                            "exclus": "régimes 50 % et 25 %", "startDate": args.start_date, "views": list(views)},
        "dataset": {
            "name": "Olive Tree Varieties Responses to Different level of Drought Stress",
            "source": "Majikumna et al., Mendeley Data, DOI 10.17632/22j26tpk63.1 (CC BY 4.0)",
            "excelRows": report.rows, "referencedImages": report.referenced_images,
            "usedImages": len(samples), "missing": len(report.missing), "invalid": report.invalid,
            "duplicatesRemoved": report.duplicates_removed, "split": summary,
        },
        "training": {"epochs": epoch_no, "bestEpoch": best["epoch"], "bestPhase": best["phase"], "history": history,
                     "seed": args.seed, "batchSize": args.batch_size, "durationSeconds": round(time.time() - t0)},
        "metrics": {"validation": metrics(vy, vp, args.uncertain_threshold), "test": test_metrics, "testPerView": per_view},
        "limitations": limitations,
    }
    sm.save_model(model, args.arch, args.out, meta)
    (args.out / "data_report.json").write_text(json.dumps({"missing": report.missing, "invalid": report.invalid,
                                                          "duplicates_removed": report.duplicates_removed},
                                                         ensure_ascii=False, indent=2), encoding="utf-8")
    m = test_metrics
    print("\n=== Résultats sur le jeu de test ===")
    print(f"Accuracy {m['accuracy']:.3f} | Precision {m['precision']:.3f} | Recall {m['recall']:.3f} | F1 {m['f1']:.3f}")
    cm = m["confusion_matrix"]["rows_true_cols_pred"]
    print("Matrice de confusion (lignes = réel, colonnes = prédit) :")
    print(f"                  SANS_STRESS  STRESS_HYDRIQUE\n  SANS_STRESS     {cm[0][0]:11d}  {cm[0][1]:15d}\n  STRESS_HYDRIQUE {cm[1][0]:11d}  {cm[1][1]:15d}")
    print(f"Modèle enregistré dans {args.out / sm.MODEL_FILE} ({round(time.time() - t0)} s)")
    return meta


if __name__ == "__main__":
    main()
