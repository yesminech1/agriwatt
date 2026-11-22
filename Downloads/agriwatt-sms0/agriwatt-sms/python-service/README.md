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

## Tests
python -m pytest -v   (15 tests, sans internet : Open-Meteo est simulé dans les tests uniquement)

## Hypothèses
Kc FAO-56 à vérifier · risque de coupure = proxy (chaleur + heures de pointe), pas une donnée STEG.
