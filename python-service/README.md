# AgriWatt - Service Python (FastAPI, port 8000)

## Lancer (Windows)
Double-clic sur start.bat   (ou : .venv\Scripts\Activate.ps1 puis uvicorn main:app --port 8000)
Première installation : py -m venv .venv ; .venv\Scripts\Activate.ps1 ; pip install -r requirements.txt

## Endpoints (doc : http://localhost:8000/docs)
GET /health
GET /forecast?lat=&lon=&crop=&areaM2=&panelAreaM2=&panelEfficiency=&days=[&forceOutageStart=&forceOutageEnd=]
    -> météo RÉELLE Open-Meteo à la position (cache des prévisions du jour si Open-Meteo ne répond pas),
       sinon HTTP 503 explicite. Aucune donnée fictive.
POST /anomalies/detect  {"points":[{"timestamp","powerKw"}]} -> Isolation Forest sur des mesures réelles importées
GET  /detection/model   -> état du modèle de stress hydrique, plantes prises en charge, métriques mesurées sur le test
POST /detection/analyze (multipart : file = JPG/PNG ≤ 10 Mo, plantType = olivier)
    -> {success, prediction (SANS_STRESS | STRESS_HYDRIQUE | INCERTAIN), modelClass, confidence, probabilities,
        interpretation, recommendation, modelVersion}. 400 image refusée, 503 modèle absent.
    Appelé uniquement par Spring Boot (/api/detection/**), jamais directement par le navigateur.

## Modèle de détection du stress hydrique
Entraînement : voir ml/README.md  (python ml/train.py --images archive1.zip archive2.zip, sans décompression)

## Tests
python -m pytest -v   (24 tests, sans internet : Open-Meteo simulé ; la détection est testée avec de petites images
                       synthétiques générées par le test, qui servent à vérifier le code et non à mesurer une performance)

## Hypothèses
Kc FAO-56 à vérifier · risque de coupure = proxy (chaleur + heures de pointe), pas une donnée STEG.
