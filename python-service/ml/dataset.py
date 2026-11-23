"""Préparation du dataset « Olive Tree Varieties Responses to Different level of Drought Stress »
(Majikumna et al., Mendeley Data, DOI 10.17632/22j26tpk63.1, licence CC BY 4.0).

- Growth.xlsx : 1 520 lignes = 80 oliviers × 19 séances hebdomadaires (mars → juillet 2024).
- Colonnes « Top view image ID » (drought2_<n>) et « side view image ID » (drought_<n>) : <n> = numéro de ligne.
- Les images (2 × 1 440 JPG) ne sont PAS dans le fichier Excel : il faut les télécharger depuis Mendeley.
  Les archives .zip téléchargées peuvent être lues telles quelles, sans décompression.

Étiquettes : le dataset ne contient pas de label « stressé / non stressé » observé, seulement le régime
d'irrigation APPLIQUÉ. On en déduit deux classes sans rien inventer :
  - SANS_STRESS     = arbres irrigués à 100 % des besoins (groupes A1, A2) ;
  - STRESS_HYDRIQUE = arbres non irrigués, 0 % (groupes D1, D2) ;
  - 50 % et 25 % sont exclus (niveaux intermédiaires, visuellement ambigus) ;
  - seules les séances à partir de --start-date sont gardées : au début de l'essai, un arbre non irrigué
    n'a pas encore eu le temps de montrer des signes de stress.
"""
from __future__ import annotations

import hashlib
import random
import zipfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

VIEWS = {"top": "Top view image ID", "side": "side view image ID"}
IMAGE_EXTS = {".jpg", ".jpeg", ".png"}
DEFAULT_START_DATE = "2024-05-01"
CONTROL_REGIMES = ("100%",)
STRESS_REGIMES = ("0%",)


@dataclass
class Sample:
    row: int
    tree: str
    variety: str
    regime: str
    date: str
    view: str
    image_id: str
    label: int
    path: "ImageRef | None" = None
    split: str = ""
    md5: str = ""


@dataclass
class DataReport:
    rows: int = 0
    rows_selected: int = 0
    referenced_images: int = 0
    found: int = 0
    missing: list = field(default_factory=list)
    invalid: list = field(default_factory=list)
    duplicates_removed: list = field(default_factory=list)
    ambiguous_names: list = field(default_factory=list)


def read_growth(excel: Path) -> pd.DataFrame:
    df = pd.read_excel(excel)
    df.columns = [str(c).strip() for c in df.columns]
    needed = ["Date", "Olive_ID", "Irrigation Regime", "Variety", *VIEWS.values()]
    missing = [c for c in needed if c not in df.columns]
    if missing:
        raise ValueError(f"Colonnes absentes de {excel.name} : {missing}")
    df["Irrigation Regime"] = df["Irrigation Regime"].astype(str).str.strip()
    return df


def build_samples(df: pd.DataFrame, start_date: str = DEFAULT_START_DATE,
                  control=CONTROL_REGIMES, stress=STRESS_REGIMES, views=("top", "side")) -> list[Sample]:
    """Une entrée par (ligne, vue) étiquetée. Aucune image n'est lue ici."""
    start = pd.Timestamp(start_date)
    out = []
    for idx, r in df.iterrows():
        regime = r["Irrigation Regime"]
        if regime in control:
            label = 0
        elif regime in stress:
            label = 1
        else:
            continue
        if pd.Timestamp(r["Date"]) < start:
            continue
        for view in views:
            image_id = r[VIEWS[view]]
            if pd.isna(image_id) or not str(image_id).strip():
                continue
            image_id = str(image_id).strip()
            num = image_id.rsplit("_", 1)[-1]
            if num.isdigit() and int(num) != idx:
                raise ValueError(f"Identifiant {image_id} incohérent avec la ligne {idx} : association image/étiquette douteuse.")
            out.append(Sample(row=int(idx), tree=str(r["Olive_ID"]).strip(), variety=str(r["Variety"]).strip(),
                              regime=regime, date=pd.Timestamp(r["Date"]).date().isoformat(),
                              view=view, image_id=image_id, label=label))
    return out


@dataclass(frozen=True)
class ImageRef:
    """Emplacement d'une image : fichier normal, membre d'une archive .zip, ou d'un .zip rangé dans un .zip.
    Les archives Mendeley n'ont donc PAS besoin d'être décompressées."""
    chain: tuple          # (fichier,) ou (archive.zip, membre) ou (archive.zip, interne.zip, membre)
    size: int = 0

    def __str__(self):
        return " :: ".join(str(c) for c in self.chain)


class ImageReader:
    """Lit les octets d'une image, en gardant les archives ouvertes (lecture directe dans le .zip)."""
    def __init__(self):
        self._zips = {}

    def _zip(self, chain: tuple) -> zipfile.ZipFile:
        if chain not in self._zips:
            if len(chain) == 1:
                self._zips[chain] = zipfile.ZipFile(chain[0])
            else:
                self._zips[chain] = zipfile.ZipFile(self._zip(chain[:-1]).open(chain[-1]))
        return self._zips[chain]

    def read(self, ref: ImageRef) -> bytes:
        if len(ref.chain) == 1:
            return Path(ref.chain[0]).read_bytes()
        return self._zip(ref.chain[:-1]).read(ref.chain[-1])

    def close(self):
        for z in reversed(list(self._zips.values())):
            z.close()
        self._zips.clear()


def index_images(sources, reader: ImageReader | None = None) -> tuple[dict, list]:
    """Nom de fichier (sans extension, minuscules) -> ImageRef, dans des dossiers et/ou des archives .zip
    (y compris un .zip contenu dans un .zip). Un même nom présent deux fois avec la même taille n'est gardé qu'une fois."""
    reader = reader or ImageReader()
    found, ambiguous = {}, set()

    def add(key, ref):
        if key in found and found[key].size != ref.size:
            ambiguous.add(key)
        found.setdefault(key, ref)

    def scan_zip(chain):
        for info in reader._zip(chain).infolist():
            if info.is_dir():
                continue
            name = info.filename
            base = name.replace("\\", "/").rsplit("/", 1)[-1]
            if base.startswith("._") or "__MACOSX" in name:      # fichiers parasites des archives macOS
                continue
            suffix = Path(base).suffix.lower()
            if suffix in IMAGE_EXTS:
                add(Path(base).stem.lower(), ImageRef(chain + (name,), info.file_size))
            elif suffix == ".zip" and len(chain) < 2:
                scan_zip(chain + (name,))

    for src in sources:
        src = Path(src)
        if src.is_file() and src.suffix.lower() == ".zip":
            scan_zip((str(src),))
        elif src.is_dir():
            for p in sorted(src.rglob("*")):
                if not p.is_file() or p.name.startswith("._"):
                    continue
                if p.suffix.lower() in IMAGE_EXTS:
                    add(p.stem.lower(), ImageRef((str(p),), p.stat().st_size))
                elif p.suffix.lower() == ".zip":
                    scan_zip((str(p),))
        else:
            raise FileNotFoundError(f"Introuvable (dossier ou archive .zip attendu) : {src}")
    return found, sorted(ambiguous)


def attach_images(samples: list[Sample], sources, report: DataReport, check_image, reader: ImageReader) -> list[Sample]:
    """Associe chaque échantillon à son image, écarte les manquants, les invalides et les doublons.
    check_image(octets) renvoie None si l'image est exploitable, sinon la raison du rejet."""
    index, ambiguous = index_images(sources, reader)
    wanted = {s.image_id.lower() for s in samples}
    report.ambiguous_names = [a for a in ambiguous if a in wanted]
    kept = []
    for i, s in enumerate(samples, 1):
        ref = index.get(s.image_id.lower())
        if ref is None:
            report.missing.append(s.image_id)
            continue
        if s.image_id.lower() in report.ambiguous_names:
            report.invalid.append({"image": s.image_id, "reason": "plusieurs fichiers différents portent ce nom"})
            continue
        try:
            data = reader.read(ref)
        except Exception as e:  # noqa: BLE001 (archive endommagée, membre illisible)
            report.invalid.append({"image": s.image_id, "reason": f"lecture impossible ({type(e).__name__})"})
            continue
        reason = check_image(data)
        if reason:
            report.invalid.append({"image": s.image_id, "reason": reason})
            continue
        s.path = ref
        s.md5 = hashlib.md5(data).hexdigest()
        kept.append(s)
        if i % 200 == 0:
            print(f"   {i}/{len(samples)} images contrôlées")
    report.found = len(kept) + len(report.invalid)

    # Doublons exacts : même contenu sous plusieurs identifiants.
    groups = defaultdict(list)
    for s in kept:
        groups[s.md5].append(s)
    final = []
    for same in groups.values():
        if len(same) == 1:
            final.append(same[0])
            continue
        trees, labels = {s.tree for s in same}, {s.label for s in same}
        if len(trees) == 1 and len(labels) == 1:
            final.append(same[0])                       # même arbre : on garde une seule copie
            report.duplicates_removed += [s.image_id for s in same[1:]]
        else:                                           # contenu partagé entre arbres/classes : association douteuse
            report.duplicates_removed += [s.image_id for s in same]
    final.sort(key=lambda s: (s.row, s.view))
    return final


def split_trees(samples: list[Sample], ratios=(0.70, 0.15, 0.15), seed: int = 42) -> dict:
    """Découpage PAR ARBRE (jamais par image), stratifié par classe et par variété.
    Toutes les photos d'un même arbre vont dans le même ensemble : aucune fuite entre entraînement et test."""
    trees = {}
    for s in samples:
        trees[s.tree] = (s.label, s.variety)
    strata = defaultdict(list)
    for tree, key in sorted(trees.items()):
        strata[key].append(tree)
    rng = random.Random(seed)
    assignment = {}
    for key in sorted(strata):
        group = strata[key]
        rng.shuffle(group)
        n = len(group)
        n_test = max(1, round(n * ratios[2])) if n >= 3 else 0
        n_val = max(1, round(n * ratios[1])) if n >= 3 else 0
        for i, tree in enumerate(group):
            assignment[tree] = "test" if i < n_test else ("val" if i < n_test + n_val else "train")
    for s in samples:
        s.split = assignment[s.tree]
    return assignment


def summarize(samples: list[Sample]) -> dict:
    out = {}
    for split in ("train", "val", "test"):
        part = [s for s in samples if s.split == split]
        out[split] = {
            "images": len(part),
            "trees": len({s.tree for s in part}),
            "par_classe": {"SANS_STRESS": sum(s.label == 0 for s in part), "STRESS_HYDRIQUE": sum(s.label == 1 for s in part)},
            "par_vue": {v: sum(s.view == v for s in part) for v in VIEWS},
            "arbres": sorted({s.tree for s in part}),
        }
    return out
