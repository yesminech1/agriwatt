# Modèle de détection du stress hydrique (olivier)

## Données
- `data/Growth.xlsx` : fichier du dataset **Olive Tree Varieties Responses to Different level of Drought Stress**
  (Majikumna et al., Mendeley Data, DOI 10.17632/22j26tpk63.1, licence CC BY 4.0).
- Les **images ne sont pas dans le fichier Excel** (2 archives .zip de 4,8 Go, sur
  https://data.mendeley.com/datasets/22j26tpk63/1). **Inutile de les décompresser** : le script lit les photos
  directement dans les archives et n'écrit que de petites copies redimensionnées (environ 35 Mo au total dans `ml/cache/`).
  Les images sont retrouvées par leur nom (`drought2_<n>` = vue de dessus, `drought_<n>` = vue de côté ;
  `<n>` = numéro de ligne dans Growth.xlsx). Des dossiers d'images déjà décompressées sont aussi acceptés.

## Étiquettes (rien n'est inventé)
Le dataset ne contient pas d'étiquette « stressé / non stressé » observée, seulement le **régime d'irrigation appliqué** :
| Classe | Arbres retenus |
|---|---|
| `SANS_STRESS` | irrigation 100 % des besoins (groupes A1, A2) |
| `STRESS_HYDRIQUE` | aucune irrigation, 0 % (groupes D1, D2) |
| exclus | régimes 50 % et 25 % (intermédiaires, ambigus) et séances avant le 1er mai 2024 (stress pas encore visible) |

Cela donne 1 040 photos attendues (20 + 20 arbres × 13 séances × 2 vues).
Découpage **par arbre** (aucun arbre dans deux ensembles), stratifié par classe et variété :
28 arbres en entraînement (70 %), 6 en validation (15 %), 6 en test (15 %).

## Entraîner (Windows, une seule fois)
```powershell
cd python-service
.venv\Scripts\Activate.ps1
pip install --no-cache-dir -r requirements.txt     # PyTorch CPU, environ 1 Go installé
python ml/train.py --images "C:\Users\moi\Downloads\archive1.zip" "C:\Users\moi\Downloads\archive2.zip"
```
- Première exécution : les poids ImageNet de MobileNetV3 sont téléchargés (internet nécessaire).
- Durée indicative sur un PC portable : 15 à 40 minutes. Espace disque nécessaire en plus des archives : environ 1,5 Go
  (PyTorch + petites copies des photos + modèle).
- Le script affiche le nombre d'images trouvées, manquantes, invalides et en doublon, puis **Accuracy, Precision,
  Recall, F1-score et la matrice de confusion sur le jeu de test**.
- Résultat : `models/stress_model.pt` + `models/stress_model.json` (métadonnées et métriques mesurées).
  Le service FastAPI charge ce fichier **une seule fois** et ne réentraîne jamais.
- Options utiles : `--arch small` (plus rapide), `--views side` (vue de côté seulement), `--start-date 2024-06-01`,
  `--uncertain-threshold 0.7`. `python ml/train.py --help` pour la liste complète.

Le fichier `models/stress_model.pt` (environ 17 Mo) peut être poussé sur GitHub : les coéquipiers n'auront pas
besoin de télécharger les 9,6 Go d'images.

## Limites (affichées aussi dans l'application)
- Jeunes oliviers de trois variétés, photographiés pendant un seul essai (Maroc, mars–juillet 2024) : le modèle peut
  mal se comporter sur de grands arbres en verger ou sur des photos prises autrement.
- Étiquettes = traitement d'irrigation, pas mesure de l'état hydrique de chaque arbre.
- Test sur 6 arbres seulement : les métriques sont indicatives.
- Le score de confiance (softmax) n'est pas calibré. En dessous du seuil (0,65 par défaut), le résultat est « incertain ».
- Une photo ne mesure pas l'humidité du sol : AgriWatt ne modifie jamais l'irrigation sur cette seule base.
