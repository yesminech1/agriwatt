/* =====================================================================
   AgriWatt – Détection du stress hydrique des plantes (page « Détection des plantes »)
   Photo -> Spring Boot (/api/detection/**, session + CSRF) -> modèle IA du service Python.
   Tout ce qui est affiché vient de la réponse réelle du modèle ou de l'historique en base.
   Utilise les utilitaires de app.js ($, esc, API, fetchJson, nf0, dateTimeLabel), chargé juste après.
   ===================================================================== */
'use strict';

const DET_MAX_BYTES = 10 * 1024 * 1024;
const DET_TYPES = ['image/jpeg', 'image/png'];
const DET_EXT = /\.(jpe?g|png)$/i;
const DET_CLASS_LABELS = { SANS_STRESS: 'Sans stress hydrique', STRESS_HYDRIQUE: 'Stress hydrique' };
const det = { file: null, previewUrl: null, busy: false, info: null, detailId: null, ready: false };

const pct = (x) => `${nf0.format(Math.round(Number(x) * 100))} %`;

function detShow(stateId) {
  ['detIdle', 'detLoading', 'detError', 'detOutput'].forEach((id) => { $(id).hidden = id !== stateId; });
  const el = $(stateId);
  el.classList.remove('det-fade'); void el.offsetWidth; el.classList.add('det-fade');   // transition discrète
}

function detFileError(message) {
  const m = $('detFileMsg');
  m.textContent = message || '';
  m.hidden = !message;
}

function detUpdateButtons() {
  const has = !!det.file;
  $('btnDetAnalyze').disabled = !has || det.busy || !det.ready;
  $('btnDetChange').hidden = !has || det.busy;
  $('btnDetRemove').hidden = !has || det.busy;
  $('detDrop').classList.toggle('disabled', det.busy);
  $('detPlant').disabled = det.busy;
}

/** Contrôle côté navigateur (le serveur et le modèle revérifient tout). */
function detValidate(file) {
  if (!file) return 'Aucun fichier sélectionné.';
  if (!DET_EXT.test(file.name) || (file.type && !DET_TYPES.includes(file.type))) {
    return 'Format non accepté : sélectionnez une image JPG, JPEG ou PNG.';
  }
  if (file.size === 0) return 'Le fichier est vide.';
  if (file.size > DET_MAX_BYTES) return `Image trop volumineuse (${nf0.format(file.size / 1048576)} Mo) : 10 Mo maximum.`;
  return null;
}

function detClearFile() {
  if (det.previewUrl) URL.revokeObjectURL(det.previewUrl);
  det.file = null; det.previewUrl = null;
  $('detFile').value = '';
  $('detPreview').hidden = true;
  $('detDropEmpty').hidden = false;
  $('detPreviewImg').removeAttribute('src');
  detUpdateButtons();
}

function detSelectFile(file) {
  if (det.busy) return;
  const error = detValidate(file);
  if (error) {
    detClearFile(); detFileError(error);
    return;
  }
  detFileError('');
  if (det.previewUrl) URL.revokeObjectURL(det.previewUrl);
  det.file = file;
  det.previewUrl = URL.createObjectURL(file);
  const img = $('detPreviewImg');
  img.onerror = () => { detClearFile(); detFileError('Image illisible ou endommagée : sélectionnez une autre photo.'); };
  img.src = det.previewUrl;
  $('detPreviewName').textContent = `${file.name} · ${nf0.format(Math.max(1, file.size / 1024))} Ko`;
  $('detPreview').hidden = false;
  $('detDropEmpty').hidden = true;
  if (!$('detOutput').hidden || !$('detError').hidden) detShow('detIdle');   // nouvelle photo : ancien résultat retiré
  detUpdateButtons();
}

/** Bloc de résultat, réutilisé pour l'analyse en cours et pour le détail d'une analyse de l'historique. */
function detRenderAnalysis(a, imageSrc) {
  const kind = a.prediction === 'STRESS_HYDRIQUE' ? 'stress' : a.prediction === 'SANS_STRESS' ? 'ok' : 'uncertain';
  const ico = { stress: '!', ok: '✓', uncertain: '?' }[kind];
  const threshold = a.uncertainThreshold != null ? Number(a.uncertainThreshold) : null;
  const lean = kind === 'uncertain'
    ? `<p class="det-lean">Classe la plus probable selon le modèle : <b>${esc(DET_CLASS_LABELS[a.modelClass] || a.modelClass)}</b>, avec un score trop faible pour conclure.</p>` : '';
  return `
    <div class="det-verdict v-${kind}">
      <span class="det-verdict-ico" aria-hidden="true">${ico}</span>
      <div><small>État détecté · ${esc(a.plantLabel || a.plantType)}</small><b>${esc(a.predictionLabel || a.prediction)}</b></div>
    </div>
    <div class="det-out-grid">
      <figure class="det-analyzed">
        ${imageSrc ? `<img src="${esc(imageSrc)}" alt="Image analysée par le modèle">` : '<div class="det-noimg">Image non conservée</div>'}
        <figcaption>Image analysée${a.imageName ? ' · ' + esc(a.imageName) : ''}</figcaption>
      </figure>
      <div class="det-out-text">
        <div class="det-conf">
          <div class="det-conf-head"><span>Score de confiance du modèle</span><b>${pct(a.confidence)}</b></div>
          <div class="det-bar v-${kind}"><i style="width:${Math.round(a.confidence * 100)}%"></i>${threshold != null ? `<em style="left:${Math.round(threshold * 100)}%" title="Seuil d'incertitude"></em>` : ''}</div>
          <div class="det-probs">
            <span>Sans stress <b>${pct(a.probNoStress)}</b></span>
            <span>Stress hydrique <b>${pct(a.probStress)}</b></span>
          </div>
          <small>Score brut du réseau (softmax), non calibré : ce n'est pas une probabilité scientifique.${threshold != null ? ` En dessous de ${pct(threshold)}, le résultat est présenté comme incertain.` : ''}</small>
        </div>
        ${lean}
        <div class="det-block"><h4>Interprétation</h4><p>${esc(a.interpretation || '')}</p></div>
        <div class="det-block reco"><h4>Recommandation</h4><p>${esc(a.recommendation || '')}</p>
          <small>AgriWatt ne déclenche jamais l'irrigation sur la seule base d'une photo.</small></div>
      </div>
    </div>
    <p class="det-disclaimer">La détection repose sur des signes visuels et ne constitue pas une mesure directe de l'humidité interne de la plante.</p>`;
}

function detError(title, text) {
  $('detErrorTitle').textContent = title;
  $('detErrorText').textContent = text;
  detShow('detError');
}

async function detAnalyze() {
  if (!det.file || det.busy || !det.ready) return;
  const error = detValidate(det.file);
  if (error) { detFileError(error); return; }
  det.busy = true; detUpdateButtons();
  $('detSpinner').classList.remove('d-none');
  $('detAnalyzeLabel').textContent = 'Analyse en cours…';
  $('detLoadingImg').src = det.previewUrl;
  detShow('detLoading');
  const form = new FormData();
  form.append('file', det.file, det.file.name);
  form.append('plantType', $('detPlant').value);
  try {
    const a = await fetchJson(API + '/api/detection/analyze', { method: 'POST', body: form }, 60000);
    $('detOutput').innerHTML = detRenderAnalysis(a, det.previewUrl);   // l'image exacte envoyée au modèle
    detShow('detOutput');
    detLoadHistory(a.id);
  } catch (e) {
    if (e.status === 400 || e.status === 413) {
      detFileError(e.message);
      detError('Image refusée', e.message);
    } else if (e.status === 503) {
      detError('Service d\'intelligence artificielle indisponible', e.message);
    } else if (!e.status) {
      detError('Erreur de connexion', `Le serveur AgriWatt ne répond pas (${e.message}). Vérifiez que l'application est lancée puis réessayez.`);
    } else {
      detError('Analyse impossible', e.message);
    }
  } finally {
    det.busy = false;
    $('detSpinner').classList.add('d-none');
    $('detAnalyzeLabel').textContent = 'Analyser ma plante';
    detUpdateButtons();
  }
}

/* ---------- État et performances du modèle (métadonnées réelles de l'entraînement) ---------- */
function detRenderModel(info) {
  const status = $('detModelStatus');
  const plants = (info && info.plants && info.plants.length) ? info.plants : [{ code: 'olivier', label: 'Olivier' }];
  $('detPlant').innerHTML = plants.map((p) => `<option value="${esc(p.code)}">${esc(p.label)}</option>`).join('');
  det.ready = !!(info && info.available);
  if (!det.ready) {
    status.textContent = (info && info.message) || 'Modèle indisponible';
    status.className = 'det-model warn';
    $('detIdleText').textContent = 'Le modèle de détection n\'est pas disponible : ' + ((info && info.message) || 'service IA injoignable.');
    $('detPerf').hidden = true;
    detUpdateButtons();
    return;
  }
  status.textContent = `Modèle ${String(info.arch || '').replace('mobilenet_v3_', 'MobileNetV3-')} · version ${info.version} · prêt`;
  status.className = 'det-model' + (info.testOnly ? ' warn' : ' ok');
  const t = (info.metrics && info.metrics.test) || null;
  if (!t) { $('detPerf').hidden = true; detUpdateButtons(); return; }
  const split = info.dataset && info.dataset.split && info.dataset.split.test;
  $('detPerfSub').textContent = `Mesurées sur ${t.images} images de ${split ? split.trees : '?'} oliviers jamais vus pendant l'entraînement (jeu de test indépendant)`
    + (info.trainedAt ? ` · entraîné le ${dateTimeLabel(info.trainedAt)}` : '') + '.';
  $('detPerfWarn').hidden = !info.testOnly;
  $('detPerfWarn').textContent = info.testOnly
    ? 'Attention : ce modèle a été produit en mode test (sans pré-entraînement ou sur un sous-ensemble d\'images). Ses chiffres ne reflètent pas une performance réelle : relancez l\'entraînement complet.' : '';
  const tiles = [['Accuracy', t.accuracy, 'part des photos bien classées'], ['Precision', t.precision, 'stress annoncé à raison'],
    ['Recall', t.recall, 'stress réel détecté'], ['F1-score', t.f1, 'équilibre precision / recall']];
  $('detMetrics').innerHTML = tiles.map(([k, v, hint]) => `<div class="hist-kpi"><small>${k}</small><b>${pct(v)}</b><span>${hint}</span></div>`).join('')
    + (t.uncertain_rate != null ? `<div class="hist-kpi"><small>Résultats incertains</small><b>${pct(t.uncertain_rate)}</b><span>score sous le seuil</span></div>` : '');
  const cm = t.confusion_matrix && t.confusion_matrix.rows_true_cols_pred;
  $('detCm').innerHTML = cm ? `<table class="det-cm-table"><thead><tr><th>Réel \\ Prédit</th><th>Sans stress</th><th>Stress hydrique</th></tr></thead><tbody>
      <tr><th>Sans stress</th><td class="ok">${cm[0][0]}</td><td class="ko">${cm[0][1]}</td></tr>
      <tr><th>Stress hydrique</th><td class="ko">${cm[1][0]}</td><td class="ok">${cm[1][1]}</td></tr></tbody></table>` : '';
  $('detLimits').innerHTML = (info.limitations || []).map((l) => `<li>${esc(l)}</li>`).join('');
  $('detPerf').hidden = false;
  detUpdateButtons();
}

async function detLoadModel() {
  try {
    detRenderModel(await fetchJson(API + '/api/detection/model', {}, 30000));
  } catch (e) {
    detRenderModel({ available: false, message: e.status === 503 ? e.message : 'Service IA injoignable : ' + e.message });
  }
}

/* ---------- Historique personnel ---------- */
async function detLoadHistory(highlightId) {
  try {
    const list = await fetchJson(API + '/api/detection/analyses');
    $('detHistMsg').textContent = '';
    $('detHistEmpty').hidden = list.length > 0;
    $('detHistWrap').hidden = list.length === 0;
    $('detHistBody').innerHTML = list.map((a) => {
      const kind = a.prediction === 'STRESS_HYDRIQUE' ? 'stress' : a.prediction === 'SANS_STRESS' ? 'ok' : 'uncertain';
      return `<tr data-id="${a.id}" class="${a.id === highlightId ? 'det-new' : ''}">
        <td>${esc(dateTimeLabel(a.createdAt))}</td>
        <td>${a.hasImage ? `<img class="det-thumb" src="${esc(API + a.imageUrl)}" alt="" loading="lazy">` : '–'}</td>
        <td>${esc(a.plantLabel)}</td>
        <td><span class="pill det-${kind}">${esc(a.predictionLabel)}</span></td>
        <td class="num">${pct(a.confidence)}</td>
        <td><button class="btn btn-light btn-sm fw-semibold" type="button" data-det-open="${a.id}">Voir</button></td></tr>`;
    }).join('');
  } catch (e) {
    $('detHistMsg').textContent = 'Historique indisponible : ' + e.message;
  }
}

async function detOpen(id) {
  try {
    const a = await fetchJson(API + '/api/detection/analyses/' + encodeURIComponent(id));
    det.detailId = a.id;
    $('detDetailMeta').textContent = `Analyse du ${dateTimeLabel(a.createdAt)}${a.modelVersion ? ' · modèle ' + a.modelVersion : ''}`;
    $('detDetailBody').innerHTML = detRenderAnalysis(a, a.hasImage ? API + a.imageUrl : null);
    $('detDetail').hidden = false;
    $('detDetail').classList.remove('det-fade'); void $('detDetail').offsetWidth; $('detDetail').classList.add('det-fade');
    $('detDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) {
    $('detHistMsg').textContent = 'Analyse indisponible : ' + e.message;
  }
}

async function detDelete() {
  if (det.detailId == null) return;
  if (!window.confirm('Supprimer définitivement cette analyse et sa photo ?')) return;
  try {
    await fetchJson(API + '/api/detection/analyses/' + det.detailId, { method: 'DELETE' });
    det.detailId = null;
    $('detDetail').hidden = true;
    detLoadHistory();
  } catch (e) {
    $('detHistMsg').textContent = 'Suppression impossible : ' + e.message;
  }
}

/* ---------- Initialisation (appelée par app.js sur la vue « detection ») ---------- */
async function initDetection() {
  if (!$('sec-detect')) return;
  const drop = $('detDrop');
  const input = $('detFile');
  const pick = () => { if (!det.busy) input.click(); };
  drop.addEventListener('click', (e) => { if (!e.target.closest('button')) pick(); });
  drop.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(); } });
  input.addEventListener('change', () => { if (input.files && input.files[0]) detSelectFile(input.files[0]); });
  ['dragenter', 'dragover'].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); if (!det.busy) drop.classList.add('drag'); }));
  ['dragleave', 'dragend'].forEach((t) => drop.addEventListener(t, () => drop.classList.remove('drag')));
  drop.addEventListener('drop', (e) => {
    e.preventDefault(); drop.classList.remove('drag');
    const files = e.dataTransfer && e.dataTransfer.files;
    if (!files || !files.length) return;
    if (files.length > 1) detFileError('Une seule image à la fois : la première a été retenue.');
    detSelectFile(files[0]);
  });
  $('btnDetChange').addEventListener('click', pick);
  $('btnDetRemove').addEventListener('click', () => { detClearFile(); detFileError(''); detShow('detIdle'); });
  $('btnDetAnalyze').addEventListener('click', detAnalyze);
  $('btnDetRetry').addEventListener('click', () => { if (det.file) detAnalyze(); else detShow('detIdle'); });
  $('detHistBody').addEventListener('click', (e) => { const b = e.target.closest('[data-det-open]'); if (b) detOpen(b.dataset.detOpen); });
  $('btnDetClose').addEventListener('click', () => { det.detailId = null; $('detDetail').hidden = true; });
  $('btnDetDelete').addEventListener('click', detDelete);
  detUpdateButtons();
  await Promise.all([detLoadModel(), detLoadHistory()]);
}
