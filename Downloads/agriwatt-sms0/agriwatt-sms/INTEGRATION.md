# Rapport d'intégration A + B + C (21/11/2026)

## Base retenue
- **Service Python** : travail A, inchangé (20 tests pytest OK).
- **Back-end** : travail B (Spring Boot 4.2, port 8082, base XAMPP `agriwatt` sur 3307, package `tn.agriwatt.backend`).
- **Interface** : front de C copié dans `backend/src/main/resources/static/`, plus `pitch/` et `download-libs.sh`.
- **Non intégré (doublons de C)** : le back-end Java parallèle de C (port 8081, son optimiseur, `SolarModel`) et son
  service Python (contrat différent : `/forecast/solar`, `/anomalies`). Ils restent disponibles dans le ZIP d'origine de C.

## Fonctionnalités de B conservées
Optimiseur par programmation dynamique, baseline 6h-10h, simulateur de réservoir, tarifs, KPIs, persistance MySQL
(`plan_run`, `schedule_hour`), `/api/debug/*`, fichiers de secours quand Python est éteint.

## Fonctionnalités de C intégrées
8 pages (tableau de bord, énergie, planning, paramètres, anomalies, historique, connexion, inscription), graphiques
Chart.js, mode démo (touche D), bascule automatique sur les mocks, bibliothèques hors ligne, **comptes utilisateurs en
base** (portés dans le back-end de B), pitch.

## Fichiers ajoutés (backend)
- `config/SecurityConfig.java` : Spring Security, BCrypt, session, CSRF par cookie, pages protégées
- `controller/AuthController.java` : `/api/auth/register`, `login`, `csrf`, `me` (logout géré par Spring Security)
- `entity/AppUser.java`, `repository/AppUserRepository.java`, `dto/AuthDtos.java` : comptes (table existante `user`)
- `dto/RunSummary.java` : format de l'historique attendu par la page Historique
- `service/PythonClientRules.java` : traduction front -> contrat Python (scénarios, coupure)
- `src/test/resources/application.properties` : tests sur H2 (sans MySQL)
- `src/test/.../AuthAndApiIntegrationTests.java`, `PythonClientRulesTests.java`
- `src/main/resources/static/**` : front de C

## Fichiers modifiés
| Fichier | Modification | Raison |
|---|---|---|
| `backend/pom.xml` | + security, spring-security-test, h2 (test) | authentification et tests sans MySQL |
| `dto/ScheduleItem.java`, `service/SimHour.java`, `service/TankSimulator.java` | + `solarKw`, `tempC` dans le planning | le front affichait une estimation au lieu du vrai `pvKw` de A |
| `service/PythonClient.java` | scénario normalisé, coupure transmise seulement si début < fin | `heatwave`/`cloudy` et la coupure 0/0 provoquaient un 422 chez A, d'où une bascule silencieuse sur le secours |
| `controller/PlanController.java` | `/api/runs` renvoie `RunSummary` (20 derniers) | la page Historique de C restait vide |
| `static/js/app.js` | API relative sur 8082, scénarios `normal`/`canicule`/`nuageux` (anciens noms acceptés) | le front visait 8081 et plantait sur `scenario: "canicule"` |
| `static/index.html` | boutons de scénario renommés, cache des scripts rafraîchi | même raison |
| `pitch/pitch.md` | KPIs du scénario intégré relevés via `/api/debug/kpis` | remplacement des chiffres hérités du prototype de C |

## Tests réalisés
- Python : `pytest`, **20/20 OK**.
- Front : chargement des 6 pages dans un navigateur simulé (jsdom), servies avec **les formats JSON exacts de B**
  et branchées sur **le vrai service Python** : aucune erreur JS ; scénarios canicule, normal et nuageux corrects
  côté Python ; coupure désactivée non transmise ; 3 graphiques de 48 points ; tableau de 48 lignes ;
  18 anomalies expliquées ; historique avec libellés. **Tout OK.**
- Java : `cd backend && .\mvnw test` — **BUILD SUCCESS**, 8 tests, 0 échec (21/11/2026).

## État de démarrage local (21/11/2026)
- Python actif sur 8000 (`/health` : `{"status":"ok"}`).
- Base XAMPP active sur 3307; les tables `user`, `agriwatt_run`, `plan_run`, `schedule_hour`, `anomaly_event` et
  `user`, `app_user` et les autres tables sont toutes dans le schéma `agriwatt`. L'authentification lit et écrit `user`.
- Spring Boot actif sur 8082. Les ports 8080 (Oracle TNSLSNR) et 8081 (ancien service Java de C) sont occupés.
- Compte de démo créé : `demo@agriwatt.local`.
- Slide 5 actualisée avec les KPIs calculés par l'application intégrée.


## Gestion multi-fermes (21/11/2026, 23h)
- **Back-end ajouté** : `entity/Farm.java` (ManyToOne LAZY vers `AppUser`, `user_id` non nul), `repository/FarmRepository.java`
  (`findByIdAndUserId`, `findAllByUserIdOrderByCreatedAtAsc`), `service/FarmService.java`, `controller/FarmController.java`,
  `controller/FarmExceptionHandler.java` (400 avec erreurs par champ, 404), `dto/FarmDtos.java` (validation Bean Validation).
- **Modifié** : `AppUser.java` (`@OneToMany(mappedBy="user")` + `@JsonIgnore`, rien d'autre), `AuthAndApiIntegrationTests.java`
  (suppression des fermes avant les utilisateurs), `index.html` (section « Mes fermes », champs nom / lat / lon / rendement /
  jours / tarifs ajoutés au formulaire existant, cultures corrigées olive|datte|tomate|ble), `app.js`, `style.css`, `auth.js`.
- **Sécurité** : propriétaire = `authentication.getName()` (e-mail, connexion par formulaire ou Google). Le JSON n'a pas de
  champ propriétaire ; s'il en contient un, il est ignoré. Ferme d'un autre compte : 404.
- **Tests** : `FarmIntegrationTests.java` (scénarios 1 à 5 + validation, deux utilisateurs réels sur H2). Côté front,
  parcours complet vérifié dans un navigateur simulé (jsdom).


## Carte de localisation (21/11/2026, 23h45)
- **Dépendance** : Leaflet 1.9.4 (fichiers locaux `static/lib/leaflet`, licence BSD-2 incluse). Rien d'ajouté au pom ni à requirements.txt.
- **Python** : `main.py` (`/forecast` renvoie `source`, `lat`, `lon` ; lat/lon validées), `weather.py` (source « fallback »
  quand la météo est indisponible pour la position), 3 tests ajoutés (Tozeur/Sfax interrogent Open-Meteo à leurs coordonnées).
- **Spring Boot** : `service/TunisiaGeo.java` (nouveau), `FarmDtos` (bornes 30-38,5 / 7-12), `FarmService` (contour Tunisie),
  `PlanRequest` (+`farmId` optionnel), `PlanService` (coordonnées de la ferme relues en base, propriétaire vérifié),
  `PlanResponse` (+`weatherSource`, `lat`, `lon`), `ForecastResponse` (+`source`, `lat`, `lon`), `PythonClient`
  (secours marqué « secours-local »), `PlanController` (404 si `farmId` d'un autre compte),
  `LocationIntegrationTests.java` (tests 1 à 6 avec un faux service Python qui enregistre les coordonnées reçues).
- **Front** : `index.html` (carte dans le formulaire existant, bouton « Météo réelle », badge de source), `settings.html` (CSS Leaflet),
  `page-shell.js` (charge Leaflet sur Paramètres), `app.js` (carte, recherche, validation, source météo), `style.css`.


## Application réelle : suppression du mode démo, historique et anomalies dynamiques (22/11/2026)
### Supprimé
- Front : indicateurs « Données simulées » et « Connecté · Spring Boot », bouton et carte « Mode démo », touche D,
  scénarios (Météo réelle / Journée normale / Canicule + coupure / Journée nuageuse), interrupteur « mode simulé »,
  dossier `static/mock/`, estimation solaire de secours, section « Mes fermes » du tableau de bord.
- Spring Boot : fichiers `forecast-fallback.json` et `anomalies-fallback.json` (données fictives substituées),
  `GET /api/anomalies` (télémétrie simulée), champ `scenario` de `PlanRequest`, `PythonClientRules.normalizeScenario`.
- Python : scénarios simulés (`make_scenario`, `cache/scenario_canicule.csv`), secours « canicule » quand la météo manque,
  `GET /anomalies/demo` et `simulate_pump.py`, paramètre `scenario` de `/forecast`.
### Ajouté / modifié
- Python : `/forecast` = météo réelle uniquement (cache accepté seulement s'il contient les prévisions du jour), sinon 503 ;
  tests réécrits (15) avec un faux Open-Meteo.
- Spring Boot : `PlanService` (farmId obligatoire, tous les paramètres relus en base, enregistrement systématique avec KPI),
  `HistoryService` + endpoints historique, `AnomalyService` + `AnomalyController` (import de mesures par ferme),
  `ApiExceptionHandler` (400 / 404 / 503 au format {"message"}), `ServiceUnavailableException`, DTO `RunDtos`, `AnomalyDtos`,
  suppression d'une ferme = suppression de son historique et de ses mesures.
- Tests JUnit : `FakePythonTestBase` (faux service Python), `HistoryIntegrationTests`, `AnomalyIntegrationTests`,
  mises à jour de `FarmIntegrationTests`, `LocationIntegrationTests`, `AuthAndApiIntegrationTests`, `PythonClientRulesTests`.
- Front : `index.html` (historique, anomalies, état « aucune ferme », bandeau avec « Réessayer »), `app.js`, `style.css`.
### Base de données (appliqué automatiquement par Hibernate, `ddl-auto=update`, aucune donnée supprimée)
Équivalent SQL pour MySQL, si vous préférez l'appliquer à la main :
```sql
ALTER TABLE plan_run ADD COLUMN farm_id BIGINT NULL, ADD COLUMN lat DOUBLE NULL, ADD COLUMN lon DOUBLE NULL,
  ADD COLUMN crop VARCHAR(20) NULL, ADD COLUMN area_m2 DOUBLE NULL, ADD COLUMN days INT NULL,
  ADD COLUMN forecast_start VARCHAR(16) NULL, ADD COLUMN forecast_end VARCHAR(16) NULL, ADD COLUMN weather_source VARCHAR(20) NULL,
  ADD COLUMN solar_kwh DOUBLE NULL, ADD COLUMN grid_kwh DOUBLE NULL, ADD COLUMN pump_energy_kwh DOUBLE NULL,
  ADD COLUMN water_need_m3 DOUBLE NULL, ADD COLUMN water_delivered_m3 DOUBLE NULL, ADD COLUMN min_tank_level_m3 DOUBLE NULL,
  ADD COLUMN final_tank_level_m3 DOUBLE NULL, ADD COLUMN savings_tnd DOUBLE NULL, ADD COLUMN cost_optimized_tnd DOUBLE NULL,
  ADD COLUMN cost_baseline_tnd DOUBLE NULL, ADD COLUMN solar_share_pct DOUBLE NULL, ADD COLUMN grid_peak_kwh_avoided DOUBLE NULL,
  ADD COLUMN outage_hours_covered INT NULL, ADD COLUMN baseline_outage_hours_covered INT NULL,
  ADD CONSTRAINT fk_run_farm FOREIGN KEY (farm_id) REFERENCES farm(id), ADD INDEX idx_run_farm_date (farm_id, created_at);
ALTER TABLE schedule_hour ADD COLUMN solar_kw DOUBLE NULL, ADD COLUMN temp_c DOUBLE NULL;
ALTER TABLE anomaly_event ADD COLUMN farm_id BIGINT NULL, ADD COLUMN imported_at DATETIME(6) NULL, ADD COLUMN file_name VARCHAR(255) NULL,
  ADD CONSTRAINT fk_anomaly_farm FOREIGN KEY (farm_id) REFERENCES farm(id), ADD INDEX idx_anomaly_farm (farm_id);
```
Les anciens calculs (sans ferme) restent en base mais ne sont visibles par personne.


## Alertes SMS avec TextBee (22/11/2026)
- **Aucune dépendance ajoutée** (RestClient de Spring, déjà utilisé). Aucun code Twilio n'existait dans le projet.
- **Créés** : `service/TextBeeSmsService.java`, `TextBeeException.java`, `PhoneNumbers.java`, `OutageAlertService.java`,
  `config/AlertSchedulingConfig.java`, `entity/SmsAlert.java`, `repository/SmsAlertRepository.java`,
  `controller/SmsAlertController.java`, `dto/SmsDtos.java`, tests `TextBeeSmsServiceTests.java`, `SmsAlertIntegrationTests.java`.
- **Modifiés** : `AppUser` (+ `phone_number`, `sms_alerts_enabled`), `AppUserRepository`, `AuthDtos`, `AuthController`
  (numéro et opt-in à l'inscription), `PlanService` (programmation des alertes après chaque calcul), `FarmService`
  (suppression des alertes avec la ferme), `application.properties` (clé lue depuis `TEXTBEE_API_KEY`),
  `register.html`, `auth.js`, `index.html`, `app.js`, `style.css` ; tests : `FakePythonTestBase` (faux TextBee, UTF-8),
  `AuthAndApiIntegrationTests`.
- **Base de données** (Hibernate `ddl-auto=update`, aucune donnée supprimée) :
```sql
ALTER TABLE `user` ADD COLUMN phone_number VARCHAR(20) NULL, ADD COLUMN sms_alerts_enabled BIT NULL;
CREATE TABLE sms_alert (id BIGINT AUTO_INCREMENT PRIMARY KEY, version BIGINT, user_id BIGINT NOT NULL, farm_id BIGINT NULL,
  kind VARCHAR(10) NOT NULL, status VARCHAR(10) NOT NULL, outage_start DATETIME(6), outage_end DATETIME(6), risk_max DOUBLE,
  planned_outage BIT, scheduled_at DATETIME(6) NOT NULL, next_attempt_at DATETIME(6), message VARCHAR(500) NOT NULL,
  recipient VARCHAR(20), sms_batch_id VARCHAR(64), provider_status VARCHAR(20), delivery_checked_at DATETIME(6),
  attempts INT NOT NULL, last_error VARCHAR(500), created_at DATETIME(6), sent_at DATETIME(6), updated_at DATETIME(6),
  CONSTRAINT uk_alert_farm_outage UNIQUE (farm_id, outage_start),
  CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES `user`(id), CONSTRAINT fk_alert_farm FOREIGN KEY (farm_id) REFERENCES farm(id),
  INDEX idx_alert_status_due (status, scheduled_at), INDEX idx_alert_user (user_id));
```
