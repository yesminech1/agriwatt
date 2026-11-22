# AgriWatt – planificateur énergétique de l'irrigation
Hackathon Kizuna Japon × Tunisie. Équipe : A (service Python), B (back-end Spring Boot), C (interface web + pitch).

```
Navigateur (HTML/CSS/JS + Chart.js)  ->  Spring Boot :8082 (MySQL)  ->  Service Python FastAPI :8000
   backend/src/main/resources/static      backend/                        python-service/
```

## Lancer les trois parties (dans cet ordre)

### 1. Service Python (port 8000)
```
cd python-service
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --port 8000        (ensuite : double-clic sur start.bat)
```
Vérification : http://localhost:8000/health -> {"status":"ok"}

### 2. Base XAMPP (une seule fois)
```sql
CREATE DATABASE IF NOT EXISTS agriwatt CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'agriwatt'@'localhost' IDENTIFIED BY 'agriwatt123';
GRANT ALL PRIVILEGES ON agriwatt.* TO 'agriwatt'@'localhost';
```
Serveur XAMPP : `127.0.0.1:3307`, base unique : `agriwatt`. Le back-end lit l'URL et le compte dans
`backend/src/main/resources/application.properties` (variables possibles : `AGRIWATT_DB_HOST`, `AGRIWATT_DB_PORT`,
`AGRIWATT_DB_USERNAME`, `AGRIWATT_DB_PASSWORD`). Hibernate crée ses tables (`plan_run`, `schedule_hour`,
`anomaly_event`, `app_user`) dans cette même base au premier démarrage. L'interface utilise la table de comptes
existante `user` (colonnes `full_name`, `email`, `password_hash`, `created_at`) afin de réutiliser les comptes
déjà présents dans `agriwatt`. Les anciens comptes de `app_user` peuvent être recopiés vers `user` sans doublons.

### 3. Spring Boot (port 8082, JDK 17+)
```
cd backend
.\mvnw spring-boot:run
```
Ouvrir **http://localhost:8082** -> page de connexion -> « Créer un compte » au premier lancement.

## Fonctionnement
- **Comptes** : inscription / connexion stockées en base (`user`, mot de passe haché BCrypt), session + CSRF.
  Toutes les pages et `/api/**` exigent d'être connecté (sauf login / inscription).
- **Google** : OAuth2 Login crée ou retrouve les comptes à partir d'une adresse Google vérifiée et les stocke dans `user`.
  Apple n'est pas proposé. Pour activer Google en local, créez un client OAuth de type « Application Web » dans Google
  Cloud Console, ajoutez `http://localhost:8082/login/oauth2/code/google` comme URI de redirection autorisée, puis
  définissez `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` et
  `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` avant de lancer Spring Boot. Gardez le secret
  hors du dépôt. Sans ces identifiants, l'application démarre normalement et le bouton Google reste désactivé.
- **API (Spring Boot)** : `GET /api/config/default` (valeurs proposées pour une nouvelle ferme), `POST /api/plan` (`farmId`
  obligatoire), `GET /api/farms/{id}/runs?from=&to=`, `GET /api/runs`, `GET /api/runs/{id}`,
  `GET|POST|DELETE /api/farms/{id}/anomalies`, `GET|POST /api/farms`, `GET|PUT|DELETE /api/farms/{id}`,
  `/api/auth/register|login|logout|csrf|me`, `/api/debug/*` (outils de développement de B),
  `GET|PUT /api/account/alerts`, `POST /api/account/alerts/test`, `GET /api/account/alerts/history`, `GET /api/sms/status`.
- **Données réelles uniquement** : plus de mode démo, de scénarios ni de données mock. Chaque calcul utilise la ferme
  active : Spring Boot relit TOUS ses paramètres en base (propriétaire vérifié) puis demande au service Python la météo
  Open-Meteo à ses coordonnées. Si la météo est indisponible (ni internet ni prévisions du jour en cache), l'API renvoie
  503 avec un message clair et l'interface propose « Réessayer » : aucune donnée fictive n'est substituée.
- **Mes fermes** (page Paramètres uniquement) : chaque utilisateur gère ses fermes (table `farm`, `user_id`) ; la ferme
  sélectionnée est la ferme active de toutes les pages (tableau de bord, énergie, planning, historique, anomalies).
- **Carte** : Leaflet 1.9.4 local, OpenStreetMap, recherche Nominatim (Tunisie), contrôle « Tunisie » côté serveur.
- **Historique** : chaque calcul est enregistré (`plan_run` lié à `farm`, + 96 lignes `schedule_hour`) avec ses KPI ;
  page Historique : sélecteur de ferme, filtre par dates, détail d'un calcul relu en base (sans recalcul).
- **Anomalies** : import CSV des mesures réelles de la pompe (`horodatage;kW`) par ferme, analyse Isolation Forest
  (service Python), résultats enregistrés dans `anomaly_event` (un nouvel import remplace le précédent).
- **Plan B hors ligne** : prévisions Open-Meteo du jour en cache côté Python (une fois récupérées avec internet) ;
  bibliothèques front en local. Le fond de carte nécessite internet.

## Alertes SMS de coupure (TextBee)
TextBee envoie les SMS depuis un téléphone Android « passerelle » associé à votre compte (https://textbee.dev).
1. Créez un compte TextBee (e-mail vérifié), installez l'application Android, connectez le téléphone, autorisez les SMS.
2. Créez une **nouvelle** clé API dans le tableau de bord TextBee (ne réutilisez jamais une clé déjà exposée).
3. Définissez la clé en variable d'environnement (jamais dans le code ni dans Git), puis ouvrez un NOUVEAU terminal :
   ```powershell
   setx TEXTBEE_API_KEY "votre-cle"
   # facultatif : téléphone précis (sinon le téléphone par défaut) -> setx TEXTBEE_DEVICE_ID "id-du-telephone"
   ```
4. Relancez Spring Boot. Paramètres -> « Alertes SMS de coupure » : la pastille indique si la passerelle est opérationnelle.

Fonctionnement : à chaque calcul, et toutes les 3 heures pour les abonnés, la prévision de la ferme (risque de coupure
du service Python : 1,0 pendant les horaires de coupure saisis, sinon estimation chaleur + heures de pointe) est analysée.
Chaque coupure prévue crée UNE alerte (`sms_alert`, unique par ferme + heure de début) programmée 2 h avant
(`agriwatt.alerts.lead-hours`). Toutes les minutes, les alertes échues sont envoyées : PENDING -> SENT (accepté par TextBee,
`smsBatchId` conservé), FAILED (numéro refusé, échecs répétés, livraison échouée) ou CANCELLED (alertes désactivées,
coupure plus prévue, coupure déjà commencée). Toutes les 5 minutes, la livraison est suivie via
`GET /gateway/messages?smsBatchId=…` (pending, sent, delivered, failed). Les alertes sont en base : elles survivent aux redémarrages.

Test manuel de la connexion (PowerShell, la clé reste dans la variable) :
```powershell
curl.exe -s "https://api.textbee.dev/api/v1/gateway/devices" -H "x-api-key: $env:TEXTBEE_API_KEY"
```

## Tests
- Python : `cd python-service && python -m pytest -v` (15 tests, sans internet : Open-Meteo simulé dans les tests)
- Spring Boot : `cd backend && .\mvnw test` (base H2 en mémoire ; un faux service Python est lancé par les tests)

## Hypothèses (à dire au jury)
Météo réelle Open-Meteo · risque de coupure = estimation par proxy (chaleur + heures de pointe), pas une donnée STEG ·
tarifs en hypothèse · Kc FAO-56 à vérifier · anomalies calculées sur les mesures importées par l'utilisateur.
