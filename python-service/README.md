# AgriWatt - Service Python (FastAPI, port 8000)

## Lancer (Windows / PowerShell)
    cd python-service
    py -m venv .venv
    .venv\Scripts\Activate.ps1
    pip install -r requirements.txt
    uvicorn main:app --reload --port 8000

Doc interactive : http://localhost:8000/docs
Endpoints : GET /health, GET /forecast, GET /anomalies/demo?days=7, POST /anomalies/detect
Tests manuels : test.http (extension REST Client)
Etat : étape 1, données factices conformes au contrat (format figé).