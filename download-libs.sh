#!/usr/bin/env bash
# Télécharge Bootstrap 5 et Chart.js 4 en local (démo hors ligne). À lancer UNE fois avec internet (Git Bash OK).
set -e
D=backend/src/main/resources/static/lib
curl -L -o $D/bootstrap/bootstrap.min.css https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css
curl -L -o $D/chartjs/chart.umd.min.js https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js
echo "OK : bibliothèques copiées dans $D"
