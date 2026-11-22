# Pitch AgriWatt (3 min) – application réelle (météo Open-Meteo, données de vos fermes)

> Les résultats chiffrés de la slide 5 doivent être relevés dans l'application intégrée pendant la démo ; les chiffres de l'ancien prototype de C ont été retirés.

## Slides (7)
1. **Problème** : été 2026 le plus chaud en Tunisie ; coupures = pompes à l'arrêt, champs sans eau.
2. **Persona** : exploitant de 2 ha d'oliviers à Tozeur, pompe 5 kW, 4 kW de solaire, réservoir 30 m³.
3. **Solution** : prévoir soleil + besoin en eau, décider QUAND pomper ; le réservoir = batterie ; IA d'anomalies pompe.
4. **Démo live** (script ci-dessous).
5. **Impact** (⚠ à relever à nouveau : ces chiffres datent de la version avec scénario canicule ; l'application utilise désormais la météo réelle du jour) : 50,6 % d'énergie solaire ; coût estimé de 13,71 TND contre 27,84 TND en baseline, soit 14,13 TND économisés (−50,8 %) ; 0 kWh de pointe évités ; 10 h de coupure couvertes contre 0 h en baseline. Valeurs relevées via `/api/debug/kpis` ; simulation dépendante des paramètres, pas des économies mesurées sur une installation réelle.
6. **Limites honnêtes** : risque de coupure estimé par proxy (pas de données STEG), tarifs en hypothèse, anomalies dépendantes de la qualité des mesures importées. Suite : capteurs connectés, pilotage de vanne.
7. **Lien Japon–Tunisie** : gestion fine de la demande et efficacité énergétique appliquées à l'agriculture.

## Script de démo (90 s)
- Avant la démo : lancer Python puis Spring Boot, se connecter (compte créé à l'avance).
- 0:00 Ouvrir http://localhost:8082 (bouton « Plein écran » si besoin) : tableau de bord de la ferme active.
- 0:10 Lire la phrase du héros. 0:20 Montrer le graphique : « rouge = sans AgriWatt, à sec dès 15h ; vert = tient pendant la coupure grisée ».
- 0:40 Cartes KPI. 0:55 Paramètres : sélectionner une 2e ferme (autre ville) → nouveau plan avec SA météo réelle ; ouvrir Historique.
- 1:10 Anomalies : importer le CSV de mesures de la pompe, survoler un point rouge. 1:25 Conclure.

## Plan B
Captures + vidéo ; lancer chaque calcul une fois avec internet le matin (prévisions du jour gardées en cache par le service Python) ; libs en local.

## Questions probables
- **Données ?** Météo réelle Open-Meteo à la position de chaque ferme ; paramètres et historique en base MySQL.
- **Coupures ?** Hypothèse paramétrable (heures début/fin), estimée par proxy.
- **Sans internet ?** Prévisions du jour en cache si elles ont été récupérées ; sinon message clair « météo indisponible » (aucune donnée inventée).
- **Coût ?** Logiciel de planification ; pas de matériel supplémentaire si pompe + solaire existent.
