/* =====================================================================
   AgriWatt – interface web (HTML / CSS / JavaScript pur + Bootstrap + Chart.js)
   Données RÉELLES uniquement : fermes et historique en base (Spring Boot / MySQL),
   météo Open-Meteo à la position de la ferme (service Python). Aucune donnée fictive.
   ===================================================================== */
'use strict';

/* ---------- 1. Réglages ---------- */
// Servie par Spring Boot : URL relative. Ouverte avec Live Server (5500) : on vise le back-end local.
const API = (location.protocol === 'file:' || location.port === '5500') ? 'http://localhost:8082' : '';
const TIMEOUT_MS = 20000;        // la météo réelle peut prendre quelques secondes
const COLORS = { green: '#1f8a4c', red: '#e5484d', yellow: '#f2b705', blue: '#3b82f6', ink: '#12261c', muted: '#6b7d72', grid: '#e8eee9' };

const state = { me: null, farms: [], farmId: null, farmMode: 'free', cfg: null, plan: null, charts: {}, day: 'all', tariffs: null, view: document.body.dataset.view || 'dashboard' };

/* ---------- 2. Utilitaires ---------- */
const $ = (id) => document.getElementById(id);
const nf0 = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });
const nf1 = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const hh = (iso) => iso.slice(11, 13);
const hourLabel = (iso) => `${iso.slice(8, 10)}/${iso.slice(5, 7)} ${hh(iso)}h`;
const timeLabel = (iso) => `${iso.slice(8, 10)}/${iso.slice(5, 7)} ${iso.slice(11, 16)}`;
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function wrap(text, n) {         // coupe un texte en lignes pour les infobulles Chart.js
  const out = []; let line = '';
  String(text).split(' ').forEach((w) => {
    if ((line + ' ' + w).trim().length > n) { out.push(line); line = w; } else { line = (line + ' ' + w).trim(); }
  });
  if (line) out.push(line);
  return out;
}

async function fetchJson(url, opts = {}, ms = TIMEOUT_MS) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), ms);
  try {
    const headers = new Headers(opts.headers || {});
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes((opts.method || 'GET').toUpperCase())) {
      const csrf = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
      if (csrf) headers.set('X-XSRF-TOKEN', decodeURIComponent(csrf));
    }
    const r = await fetch(url, { ...opts, headers, signal: ctl.signal });
    if (r.status === 401) { clearUserSession(); location.assign('/login.html'); throw new Error('Session expirée'); }
    if (!r.ok) {
      const body = await r.json().catch(() => null);
      const err = new Error((body && body.message) || 'HTTP ' + r.status); err.status = r.status; throw err;
    }
    return r.status === 204 ? null : await r.json();
  } catch (e) {
    const err = new Error(e.name === 'AbortError' ? 'délai dépassé' : e.message);
    if (e.status) err.status = e.status;          // code HTTP conservé (sinon : erreur réseau ou délai)
    throw err;
  } finally { clearTimeout(timer); }
}

function countUp(el, value, fmt, suffixHtml = '') {
  const reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduce) { el.innerHTML = fmt.format(value) + suffixHtml; return; }
  const t0 = performance.now(), dur = 600;
  (function step(t) {
    const k = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - k, 3);
    el.innerHTML = fmt.format(value * e) + suffixHtml;
    if (k < 1) requestAnimationFrame(step);
  })(t0);
}

/* ---------- 3. Statut, bandeau d'erreur, chargement ---------- */
/** Bandeau d'erreur explicite, avec un bouton « Réessayer » quand une action peut être relancée. */
function showBanner(msg, retry) {
  const b = $('errorBanner'); if (!b) return;
  b.innerHTML = `<span>${esc(msg)}</span>` + (retry ? ' <button type="button" class="btn btn-sm btn-dark ms-2" id="btnRetry">Réessayer</button>' : '');
  b.classList.remove('d-none');
  if (retry) $('btnRetry').addEventListener('click', () => { hideBanner(); retry(); });
}
function hideBanner() { const b = $('errorBanner'); if (b) b.classList.add('d-none'); }
function setLoading(on) {
  document.querySelector('.content').classList.toggle('is-loading', on);
  $('btnCompute').disabled = on;
  $('calcSpinner').classList.toggle('d-none', !on);
  document.getElementById('calcLabel').textContent = on ? 'Calcul en cours…' : 'Calculer le plan de la ferme';
  if (!on) updateComputeAvailability();
}

/* ---------- 4. Formulaire <-> contrat d'API ---------- */
function applyConfigToForm(c) {
  $('fLat').value = c.lat ?? 33.92;
  $('fLon').value = c.lon ?? 8.13;
  mapShow(+$('fLat').value, +$('fLon').value);
  $('fEff').value = c.panelEfficiency ?? 0.18;
  $('fDays').value = c.days ?? 2;
  if (c.tariff) { $('fTOff').value = c.tariff.offPeak; $('fTNorm').value = c.tariff.normal; $('fTPeak').value = c.tariff.peak; }
  $('fCrop').value = c.crop || 'olive';
  $('fArea').value = +(c.areaM2 / 10000).toFixed(2);
  $('fPump').value = c.pumpPowerKw;
  $('fFlow').value = c.pumpFlowM3h;
  $('fPanel').value = c.panelAreaM2;
  $('fTank').value = c.tankCapacityM3;
  $('fTankInit').value = c.tankInitialM3;
  const on = c.outageEndHour > c.outageStartHour;
  $('fOutageOn').checked = on;
  if (on) { $('fOutStart').value = c.outageStartHour; $('fOutEnd').value = c.outageEndHour; }
  $('outageHours').classList.toggle('off', !on);
  if (c.tariff) state.tariffs = c.tariff;
}

function readConfig() {
  const num = (id) => parseFloat($(id).value);
  const errors = [];
  const cfg = {
    lat: num('fLat'), lon: num('fLon'), crop: $('fCrop').value,
    areaM2: Math.round(num('fArea') * 10000), panelAreaM2: num('fPanel'), panelEfficiency: num('fEff'),
    pumpPowerKw: num('fPump'), pumpFlowM3h: num('fFlow'),
    tankCapacityM3: num('fTank'), tankInitialM3: num('fTankInit'),
    outageStartHour: 0, outageEndHour: 0, days: Math.round(num('fDays')),
    tariff: { offPeak: num('fTOff'), normal: num('fTNorm'), peak: num('fTPeak') },
    // Spring Boot relit TOUS les paramètres de la ferme en base à partir de farmId (le reste n'est qu'informatif)
    farmId: state.farmId,
  };
  [['areaM2', 'surface'], ['panelAreaM2', 'surface des panneaux'], ['pumpPowerKw', 'puissance de la pompe'],
   ['pumpFlowM3h', 'débit de la pompe'], ['tankCapacityM3', 'capacité du réservoir'], ['tankInitialM3', 'niveau initial']]
    .forEach(([k, label]) => { if (!Number.isFinite(cfg[k]) || cfg[k] < 0) errors.push(`Valeur invalide : ${label}.`); });
  if (cfg.areaM2 <= 0 || cfg.pumpPowerKw <= 0 || cfg.pumpFlowM3h <= 0 || cfg.tankCapacityM3 <= 0) errors.push('Surface, pompe, débit et réservoir doivent être supérieurs à 0.');
  if (cfg.tankInitialM3 > cfg.tankCapacityM3) errors.push('Le niveau initial dépasse la capacité du réservoir.');
  if (!Number.isFinite(cfg.lat) || !Number.isFinite(cfg.lon)) errors.push('Aucune position sélectionnée : cliquez sur la carte (page Paramètres).');
  else if (!inTunisia(cfg.lat, cfg.lon)) errors.push('La position choisie n\'est pas en Tunisie.');
  if (!(cfg.panelEfficiency > 0 && cfg.panelEfficiency <= 1)) errors.push('Le rendement des panneaux doit être compris entre 0 et 1.');
  if (!(cfg.days >= 1 && cfg.days <= 7)) errors.push('Le nombre de jours doit être compris entre 1 et 7.');
  if (!['offPeak', 'normal', 'peak'].every((k) => cfg.tariff[k] >= 0)) errors.push('Les tarifs électriques doivent être positifs ou nuls.');
  if ($('fOutageOn').checked) {
    const s = num('fOutStart'), e = num('fOutEnd');
    if (!(s >= 0 && e <= 23 && e > s)) errors.push('Coupure : l\'heure de fin doit être après l\'heure de début (0 à 23).');
    else { cfg.outageStartHour = s; cfg.outageEndHour = e; }
  }
  return { cfg, errors };
}

/* ---------- 5. Accès aux données (back-end uniquement) ---------- */
function validatePlan(p) {       // vérifie que la réponse respecte le contrat
  const problems = [];
  if (!p || !Array.isArray(p.schedule) || !p.schedule.length) throw new Error('réponse sans "schedule"');
  if (!Array.isArray(p.baselineSchedule)) problems.push('baselineSchedule absent');
  if (!p.kpis) problems.push('kpis absent');
  const need = ['hour', 'pumpOn', 'source', 'pumpKw', 'waterPumpedM3', 'waterDeliveredM3', 'tankLevelM3', 'outage'];
  const bad = need.filter((k) => !(k in p.schedule[0]));
  if (bad.length) problems.push('champs manquants dans schedule : ' + bad.join(', '));
  if (p.schedule.some((r) => !['solar', 'grid', 'none'].includes(r.source))) problems.push('source inattendue');
  if (problems.length) console.warn('[AgriWatt] écarts au contrat :', problems);
  if (!p.baselineSchedule || !p.kpis) throw new Error(problems.join(' ; '));
  return p;
}

async function loadPlan(cfg) {
  return validatePlan(await fetchJson(API + '/api/plan', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cfg),
  }));
}

/* ---------- 6. Synthèse automatique ---------- */
function pumpRuns(schedule) {
  const runs = []; let cur = null;
  schedule.forEach((r, i) => {
    if (r.pumpOn) {
      if (!cur) cur = { start: i, end: i, solar: 0, grid: 0 };
      cur.end = i; cur[r.source === 'solar' ? 'solar' : 'grid']++;
    } else if (cur) { runs.push(cur); cur = null; }
  });
  if (cur) runs.push(cur);
  return runs;
}

function outageWindow(schedule) {
  const i = schedule.findIndex((r) => r.outage);
  if (i < 0) return null;
  let j = i; while (j + 1 < schedule.length && schedule[j + 1].outage) j++;
  return { start: +hh(schedule[i].hour), end: +hh(schedule[j].hour) + 1, hours: schedule.filter((r) => r.outage).length };
}

function buildSummary(plan) {
  const s = plan.schedule, k = plan.kpis;
  const firstDay = pumpRuns(s.slice(0, 24));
  const runs = (firstDay.length ? firstDay : pumpRuns(s)).slice(0, 3);
  const when = runs.map((r) => {
    const src = r.solar >= r.grid ? 'sur solaire' : 'sur le réseau';
    return `de ${+hh(s[r.start].hour)}h à ${(+hh(s[r.end].hour) + 1) % 24 || 24}h ${src}`;
  }).join(', puis ');
  const ow = outageWindow(s);
  if (!runs.length) return 'Aucun pompage n\'est nécessaire sur la période : le réservoir suffit.';
  if (!ow) return `Pompez <b>${when}</b> : un maximum d'énergie solaire est utilisé et le réseau est évité aux heures de pointe.`;
  const total = ow.hours;
  const tail = k.outageHoursCovered >= total
    ? `le réservoir couvrira <b>la coupure de ${ow.start}h à ${ow.end}h</b> (${k.outageHoursCovered} h sur ${total} h prévues, contre ${k.baselineOutageHoursCovered} h sans AgriWatt).`
    : `le réservoir couvrira <b>${k.outageHoursCovered} h sur ${total} h</b> de coupure (contre ${k.baselineOutageHoursCovered} h sans AgriWatt) : augmentez le réservoir ou le solaire.`;
  return `Pompez <b>${when}</b> : ${tail}`;
}

/* ---------- 7. Rendu : héros, KPI, chips ---------- */
function renderHero(plan) {
  $('heroSentence').innerHTML = buildSummary(plan);
  const temps = plan.schedule.map((r) => r.tempC).filter((t) => typeof t === 'number');
  $('heroTemp').textContent = temps.length ? `${nf0.format(Math.max(...temps))} °C` : '–';
  const ow = outageWindow(plan.schedule);
  $('heroOutage').textContent = ow ? `${ow.start}h – ${ow.end}h chaque jour` : 'Aucune';
  $('heroTank').textContent = `${nf0.format(state.cfg.tankCapacityM3)} m³ · départ ${nf0.format(state.cfg.tankInitialM3)} m³`;
}

function renderKpis(plan) {
  const k = plan.kpis, ow = outageWindow(plan.schedule);
  $('kpiSolarRing').style.setProperty('--p', Math.max(0, Math.min(100, k.solarSharePct)));
  countUp($('kpiSolar'), k.solarSharePct, nf0, ' %');
  $('kpiSolarTxt').textContent = `${nf0.format(100 - k.solarSharePct)} % depuis le réseau`;
  countUp($('kpiSavings'), k.savingsTnd, nf2, ' <small>TND</small>');
  const pct = k.costBaselineTnd > 0 ? Math.round((k.savingsTnd / k.costBaselineTnd) * 100) : 0;
  $('kpiSavingsSub').innerHTML = `<b>${nf2.format(k.costOptimizedTnd)}</b> TND avec AgriWatt contre <b>${nf2.format(k.costBaselineTnd)}</b> TND sans <span class="good">(−${pct} %)</span>`;
  countUp($('kpiPeak'), k.gridPeakKwhAvoided, nf1, ' <small>kWh</small>');
  countUp($('kpiOutage'), k.outageHoursCovered, nf0, ' <small>h</small>');
  $('kpiOutageSub').innerHTML = ow
    ? `sur <b>${ow.hours} h</b> de coupure · sans AgriWatt : <span class="bad">${k.baselineOutageHoursCovered} h</span>`
    : 'Aucune coupure prévue pour cette ferme';
}

function renderTankChips(plan) {
  const s = plan.schedule, b = plan.baselineSchedule, box = $('tankChips'), ow = outageWindow(s);
  const chips = [];
  if (!ow) {
    chips.push('<span class="chip info">Aucune coupure prévue : le réservoir lisse le pompage solaire.</span>');
  } else {
    const first = s.findIndex((r) => r.outage);
    const lvlStart = first > 0 ? s[first - 1].tankLevelM3 : state.cfg.tankInitialM3;
    const lastIdx = s.map((r) => r.outage).lastIndexOf(true);
    chips.push(`<span class="chip good">AgriWatt : ${nf1.format(lvlStart)} m³ au début de la coupure, ${nf1.format(s[lastIdx].tankLevelM3)} m³ à la fin</span>`);
    const dry = b.findIndex((r) => r.outage && r.tankLevelM3 <= 0.05);
    if (dry >= 0) chips.push(`<span class="chip bad">Sans AgriWatt : réservoir à sec dès ${(+hh(b[dry].hour) + 1)}h, l'irrigation s'arrête</span>`);
    else chips.push('<span class="chip info">Sans AgriWatt : le réservoir ne se vide pas complètement</span>');
  }
  box.innerHTML = chips.join('');
}

/* ---------- 8. Graphiques Chart.js ---------- */
const outageShade = {            // grise les heures de coupure derrière les courbes
  id: 'outageShade',
  beforeDatasetsDraw(chart, _a, opt) {
    const flags = opt && opt.flags; if (!flags) return;
    const { ctx, chartArea: area, scales: { x } } = chart; if (!area || !x) return;
    const step = x.getPixelForValue(1) - x.getPixelForValue(0);
    ctx.save();
    for (let i = 0; i < flags.length; i++) {
      if (!flags[i]) continue;
      let j = i; while (j + 1 < flags.length && flags[j + 1]) j++;
      let x1, x2;
      if (opt.mode === 'end') { x1 = x.getPixelForValue(i - 1); x2 = x.getPixelForValue(j); }   // niveau en fin d'heure
      else { x1 = x.getPixelForValue(i) - step / 2; x2 = x.getPixelForValue(j) + step / 2; }  // valeur sur l'heure
      x1 = Math.max(area.left, x1); x2 = Math.min(area.right, x2);
      ctx.fillStyle = 'rgba(110,125,116,.17)';
      ctx.fillRect(x1, area.top, x2 - x1, area.bottom - area.top);
      ctx.fillStyle = 'rgba(80,95,86,.85)'; ctx.font = '700 11px ' + Chart.defaults.font.family; ctx.textAlign = 'center';
      ctx.fillText('COUPURE', (x1 + x2) / 2, area.top + 14);
      i = j;
    }
    ctx.restore();
  },
};

function baseOptions(labels, extra = {}) {
  return {
    responsive: true, maintainAspectRatio: false, animation: { duration: 700 },
    interaction: { mode: 'index', intersect: false },
    plugins: {
      legend: { display: false },
      tooltip: { backgroundColor: '#0f3d2e', padding: 11, cornerRadius: 10, titleFont: { weight: '700' }, boxPadding: 4 },
      ...(extra.plugins || {}),
    },
    scales: {
      x: { grid: { display: false }, ticks: { autoSkip: false, maxRotation: 0, color: COLORS.muted, font: { size: 11 }, callback: (v, i) => (i % 6 === 0 ? labels[i] : '') } },
      y: { beginAtZero: true, grid: { color: COLORS.grid }, border: { display: false }, ticks: { color: COLORS.muted }, ...(extra.y || {}) },
    },
  };
}

function mount(id, config) {
  if (state.charts[id]) state.charts[id].destroy();
  state.charts[id] = new Chart($(id), config);
}

function renderCharts(plan, cfg = state.cfg, ids = { tank: 'chTank', pump: 'chPump', solar: 'chSolar' }) {
  const s = plan.schedule, b = plan.baselineSchedule;
  const labels = s.map((r) => hourLabel(r.hour));
  const outage = s.map((r) => r.outage);
  const cap = Math.max(cfg.tankCapacityM3, ...s.map((r) => r.tankLevelM3), ...b.map((r) => r.tankLevelM3));

  // (a) niveau du réservoir : LE graphique du pitch
  if ($(ids.tank)) mount(ids.tank, {
    type: 'line',
    data: { labels, datasets: [
      { label: 'AgriWatt', data: s.map((r) => r.tankLevelM3), borderColor: COLORS.green, borderWidth: 3.5, tension: 0.3, pointRadius: 0, pointHoverRadius: 5, fill: true,
        backgroundColor: (c) => { const a = c.chart.chartArea; if (!a) return 'rgba(31,138,76,.12)'; const g = c.chart.ctx.createLinearGradient(0, a.top, 0, a.bottom); g.addColorStop(0, 'rgba(31,138,76,.28)'); g.addColorStop(1, 'rgba(31,138,76,0)'); return g; } },
      { label: 'Sans AgriWatt', data: b.map((r) => r.tankLevelM3), borderColor: COLORS.red, borderWidth: 3.5, tension: 0.3, pointRadius: 0, pointHoverRadius: 5, fill: false },
    ] },
    options: baseOptions(labels, {
      y: { max: Math.ceil(cap), title: { display: true, text: 'Eau dans le réservoir (m³)', color: COLORS.muted } },
      plugins: {
        outageShade: { flags: outage, mode: 'end' },
        tooltip: { backgroundColor: '#0f3d2e', padding: 11, cornerRadius: 10, callbacks: {
          label: (c) => ` ${c.dataset.label} : ${nf1.format(c.parsed.y)} m³ (${nf0.format(100 * c.parsed.y / cfg.tankCapacityM3)} %)`,
          footer: (items) => (outage[items[0].dataIndex] ? '⏻ Coupure de courant en cours' : ''),
        } },
      },
    }),
    plugins: [outageShade],
  });

  // (b) planning de pompage par source
  const colorBySource = { solar: COLORS.yellow, grid: COLORS.blue, none: 'transparent' };
  if ($(ids.pump)) mount(ids.pump, {
    type: 'bar',
    data: { labels, datasets: [{ label: 'Puissance de pompage', data: s.map((r) => (r.pumpOn ? r.pumpKw : 0)),
      backgroundColor: s.map((r) => colorBySource[r.source]), borderRadius: 4, barPercentage: 0.9, categoryPercentage: 0.95 }] },
    options: baseOptions(labels, {
      y: { max: Math.ceil(cfg.pumpPowerKw + 1), title: { display: true, text: 'kW', color: COLORS.muted } },
      plugins: { outageShade: { flags: outage, mode: 'center' }, tooltip: { backgroundColor: '#0f3d2e', padding: 11, cornerRadius: 10, callbacks: {
        label: (c) => { const r = s[c.dataIndex]; return r.source === 'none' ? ' Pompe à l\'arrêt' : ` ${r.source === 'solar' ? 'Solaire' : 'Réseau'} · ${nf1.format(r.pumpKw)} kW · ${nf1.format(r.waterPumpedM3)} m³ pompés`; },
      } } },
    }),
    plugins: [outageShade],
  });

  // (c) production solaire vs puissance de pompage
  const solar = s.map((r) => (typeof r.solarKw === 'number' ? r.solarKw : 0));
  if ($(ids.solar)) mount(ids.solar, {
    type: 'line',
    data: { labels, datasets: [
      { label: 'Production solaire', data: solar, borderColor: COLORS.yellow, backgroundColor: 'rgba(242,183,5,.22)', fill: true, tension: 0.35, borderWidth: 2.5, pointRadius: 0 },
      { label: 'Pompe AgriWatt', data: s.map((r) => r.pumpKw), borderColor: COLORS.green, stepped: 'middle', borderWidth: 2.5, pointRadius: 0, fill: false },
      { label: 'Pompe classique', data: b.map((r) => r.pumpKw), borderColor: COLORS.red, borderDash: [6, 4], stepped: 'middle', borderWidth: 2, pointRadius: 0, fill: false },
    ] },
    options: baseOptions(labels, {
      y: { title: { display: true, text: 'kW', color: COLORS.muted } },
      plugins: { outageShade: { flags: outage, mode: 'center' }, tooltip: { backgroundColor: '#0f3d2e', padding: 11, cornerRadius: 10, callbacks: {
        label: (c) => ` ${c.dataset.label} : ${nf1.format(c.parsed.y)} kW` } } },
    }),
    plugins: [outageShade],
  });
}

function renderAnomalyChart(data) {
  const pts = (data && data.points) || [];
  const labels = pts.map((p) => timeLabel(p.timestamp));
  mount('chAnom', {
    type: 'line',
    data: { labels, datasets: [{ label: 'Consommation de la pompe', data: pts.map((p) => p.powerKw), borderColor: COLORS.green, borderWidth: 2, tension: 0, fill: false,
      pointRadius: pts.map((p) => (p.isAnomaly ? 7 : 0)), pointHoverRadius: pts.map((p) => (p.isAnomaly ? 9 : 4)),
      pointBackgroundColor: pts.map((p) => (p.isAnomaly ? COLORS.red : COLORS.green)), pointBorderColor: '#fff', pointBorderWidth: 2 }] },
    options: {
      ...baseOptions(labels, { y: { max: Math.ceil(Math.max(6, ...pts.map((p) => p.powerKw)) + 1), title: { display: true, text: 'kW', color: COLORS.muted } } }),
      scales: {
        x: { grid: { display: false }, ticks: { autoSkip: false, maxRotation: 0, color: COLORS.muted, font: { size: 11 }, callback: (v, i) => (i % 12 === 0 ? labels[i].slice(0, 5) + ' ' + labels[i].slice(6) : '') } },
        y: { beginAtZero: true, max: Math.ceil(Math.max(6, ...pts.map((p) => p.powerKw)) + 1), grid: { color: COLORS.grid }, border: { display: false }, ticks: { color: COLORS.muted }, title: { display: true, text: 'kW', color: COLORS.muted } },
      },
      plugins: { legend: { display: false }, tooltip: { backgroundColor: '#0f3d2e', padding: 11, cornerRadius: 10, callbacks: {
        label: (c) => ` ${nf2.format(c.parsed.y)} kW`,
        afterLabel: (c) => { const p = pts[c.dataIndex]; return p && p.isAnomaly ? ['', '⚠ Anomalie (score ' + nf2.format(p.score) + ')', ...wrap(p.reason, 46)] : ''; },
      } } },
    },
  });

  // alertes
  const list = pts.filter((p) => p.isAnomaly).sort((a, b) => a.timestamp.localeCompare(b.timestamp));
  $('alertCount').textContent = list.length;
  $('alertList').innerHTML = list.length ? list.map((p) => `
    <div class="alert-item">
      <div class="ico">!</div>
      <div class="top">${esc(timeLabel(p.timestamp).replace(' ', ' à '))}<span>${nf1.format(p.powerKw)} kW</span></div>
      <div><p>${esc(p.reason || 'Comportement inhabituel détecté.')}</p><small>Score d'anomalie ${nf2.format(p.score)} · mesure importée</small></div>
    </div>`).join('')
    : '<div class="alert-ok">✓ Aucune anomalie détectée sur la période</div>';
}

/* ---------- 9. Tableau de planning ---------- */
function renderTable(plan = state.plan, cfg = state.cfg, bodyId = 'planBody', day = state.day) {
  if (!plan || !$(bodyId)) return;
  const cap = Math.max(cfg.tankCapacityM3, ...plan.schedule.map((r) => r.tankLevelM3));
  const rows = plan.schedule.map((r, i) => ({ r, i })).filter(({ i }) => day === 'all' || Math.floor(i / 24) === +day);
  const srcPill = { solar: '<span class="pill solar">☀ Solaire</span>', grid: '<span class="pill grid">⚡ Réseau</span>', none: '<span class="pill none">—</span>' };
  $(bodyId).innerHTML = rows.map(({ r }) => {
    const pct = Math.max(0, Math.min(100, 100 * r.tankLevelM3 / cap));
    return `<tr class="${r.outage ? 'outage' : ''}">
      <td><b>${timeLabel(r.hour)}</b>${r.outage ? '<span class="pill cut">Coupure</span>' : ''}</td>
      <td>${r.pumpOn ? '<span class="pill on">ON</span>' : '<span class="pill off">OFF</span>'}</td>
      <td>${srcPill[r.source] || esc(r.source)}</td>
      <td class="num">${r.pumpOn ? nf1.format(r.pumpKw) + ' kW' : '–'}</td>
      <td class="num">${r.pumpOn ? nf1.format(r.waterPumpedM3) + ' m³' : '–'}</td>
      <td><div class="lvl"><div class="bar"><i class="${pct < 15 ? 'low' : ''}" style="width:${pct}%"></i></div><b>${nf1.format(r.tankLevelM3)}</b></div></td>
    </tr>`;
  }).join('');
}

/* ---------- 9 ter. Carte de localisation (Leaflet + OpenStreetMap, recherche Nominatim) ---------- */
// Contour simplifié de la Tunisie {lat, lon} : MÊME contour que TunisiaGeo.java côté serveur (le serveur reste l'autorité).
const TN_OUTLINE = [[36.97, 8.60], [37.10, 9.00], [37.40, 9.20], [37.45, 9.85], [37.30, 10.35], [37.15, 11.10],
  [36.85, 11.20], [36.40, 10.75], [35.85, 10.75], [35.75, 11.00], [35.50, 11.15], [35.20, 11.25], [34.95, 11.45],
  [34.55, 11.40], [34.45, 10.65], [34.20, 10.25], [33.95, 10.25], [33.98, 10.75], [33.90, 11.15], [33.55, 11.20],
  [33.25, 11.62], [32.95, 11.62], [32.50, 11.45], [32.05, 10.85], [31.40, 10.40], [30.75, 10.30], [30.15, 9.60],
  [30.15, 9.45], [31.00, 8.95], [31.60, 8.55], [32.40, 8.00], [33.15, 7.45], [34.20, 7.45], [34.65, 8.15],
  [35.25, 8.20], [35.90, 8.20], [36.45, 8.10], [36.80, 8.35]];
function inTunisia(lat, lon) {
  if (!(lat >= 30 && lat <= 38.5 && lon >= 7 && lon <= 12)) return false;
  let inside = false;
  for (let i = 0, j = TN_OUTLINE.length - 1; i < TN_OUTLINE.length; j = i++) {
    const [yi, xi] = TN_OUTLINE[i], [yj, xj] = TN_OUTLINE[j];
    if ((yi > lat) !== (yj > lat) && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
// Chefs-lieux des 24 gouvernorats : suggestions instantanées, sans requête réseau.
const TN_PLACES = [['Tunis', 36.8065, 10.1815], ['Ariana', 36.8625, 10.1956], ['Ben Arous', 36.7531, 10.2189],
  ['Manouba', 36.8101, 10.0956], ['Nabeul', 36.4561, 10.7376], ['Zaghouan', 36.4029, 10.1429], ['Bizerte', 37.2744, 9.8739],
  ['Béja', 36.7256, 9.1817], ['Jendouba', 36.5011, 8.7802], ['Le Kef', 36.1742, 8.7049], ['Siliana', 36.0849, 9.3708],
  ['Sousse', 35.8256, 10.6084], ['Monastir', 35.7643, 10.8113], ['Mahdia', 35.5047, 11.0622], ['Sfax', 34.7406, 10.7603],
  ['Kairouan', 35.6781, 10.0963], ['Kasserine', 35.1676, 8.8365], ['Sidi Bouzid', 35.0382, 9.4849], ['Gabès', 33.8815, 10.0982],
  ['Médenine', 33.3549, 10.5055], ['Tataouine', 32.9297, 10.4518], ['Gafsa', 34.425, 8.7842], ['Tozeur', 33.9197, 8.1335],
  ['Kébili', 33.7044, 8.969]];
const NOMINATIM = 'https://nominatim.openstreetmap.org';
const geo = { map: null, marker: null, readonly: false, last: 0, cache: new Map(), reverseTimer: null, tileWarned: false };
const plain = (t) => String(t).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

function mapMessage(text, kind = 'warn') {
  const m = $('mapMsg'); if (!m) return;
  if (!text) { m.hidden = true; return; }
  m.textContent = text; m.classList.toggle('error', kind === 'error'); m.hidden = false;
}
function setMapStatus(ok) {
  const st = $('mapStatus'); if (!st) return;
  st.textContent = ok ? 'Position sélectionnée' : 'Aucune position sélectionnée';
  st.classList.toggle('ok', ok);
}

/** Respecte la politique d'usage de Nominatim : au plus 1 requête par seconde, résultats mis en cache. */
async function nominatim(path) {
  if (geo.cache.has(path)) return geo.cache.get(path);
  const wait = 1100 - (Date.now() - geo.last);
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  geo.last = Date.now();
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 8000);
  try {
    const r = await fetch(NOMINATIM + path, { headers: { Accept: 'application/json' }, signal: ctl.signal });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const data = await r.json();
    geo.cache.set(path, data);
    return data;
  } finally { clearTimeout(t); }
}

function placeName(a) {
  if (!a) return null;
  const local = a.city || a.town || a.village || a.municipality || a.suburb || a.county;
  const region = a.state || a.region || a.county;
  return [local, region && region !== local ? region : null].filter(Boolean).join(' · ') || null;
}

function reverseGeocode(lat, lon) {             // nom du lieu, temporisé : une requête quand le marqueur s'arrête
  clearTimeout(geo.reverseTimer);
  $('mapPlace').textContent = '…';
  geo.reverseTimer = setTimeout(async () => {
    try {
      const d = await nominatim(`/reverse?format=jsonv2&zoom=10&accept-language=fr&lat=${lat.toFixed(4)}&lon=${lon.toFixed(4)}`);
      if (+$('fLat').value !== lat || +$('fLon').value !== lon) return;     // position déjà changée entre-temps
      const near = TN_PLACES.reduce((b, p) => (Math.hypot(p[1] - lat, p[2] - lon) < Math.hypot(b[1] - lat, b[2] - lon) ? p : b));
      $('mapPlace').textContent = placeName(d && d.address) || ('près de ' + near[0]);
      if (d && d.address && d.address.country_code && d.address.country_code !== 'tn') {
        mapMessage('Attention : OpenStreetMap situe ce point hors de Tunisie (zone frontalière). Vérifiez l\'emplacement.', 'warn');
      }
    } catch (_) {
      const near = TN_PLACES.reduce((b, p) => (Math.hypot(p[1] - lat, p[2] - lon) < Math.hypot(b[1] - lat, b[2] - lon) ? p : b));
      $('mapPlace').textContent = 'près de ' + near[0];      // géocodage indisponible : ville la plus proche (hors ligne)
    }
  }, 700);
}

/** Affiche une position déjà connue (ferme chargée, configuration) sans la considérer comme une saisie. */
function mapShow(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || !inTunisia(lat, lon)) { mapClear(false); return; }
  setMapStatus(true); mapMessage('');
  if (!geo.map) { if ($('mapPlace')) $('mapPlace').textContent = '–'; return; }
  placeMarker(lat, lon);
  geo.map.setView([lat, lon], Math.max(geo.map.getZoom(), 9));
  reverseGeocode(lat, lon);
}

/** Aucune position (nouvelle ferme) : la sélection est obligatoire avant l'enregistrement. */
function mapClear(clearFields = true) {
  if (clearFields) { $('fLat').value = ''; $('fLon').value = ''; }
  if (geo.marker) { geo.marker.remove(); geo.marker = null; }
  setMapStatus(false);
  if ($('mapPlace')) $('mapPlace').textContent = '–';
  if (geo.map) geo.map.setView([34.0, 9.6], 6);
}

function placeMarker(lat, lon) {
  if (!geo.map) return;
  if (!geo.marker) {
    geo.marker = L.marker([lat, lon], { draggable: !geo.readonly, keyboard: true, title: 'Emplacement de l\'exploitation' }).addTo(geo.map);
    geo.marker.on('dragend', () => { const p = geo.marker.getLatLng(); selectLocation(p.lat, p.lng, 'drag'); });
  } else geo.marker.setLatLng([lat, lon]);
}

/** Sélection par l'utilisateur (clic, glisser, recherche, « Ma position », saisie) : contrôlée puis reportée dans le formulaire. */
function selectLocation(lat, lon, origin) {
  if (geo.readonly) return;
  const prev = [parseFloat($('fLat').value), parseFloat($('fLon').value)];
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) { mapMessage('Coordonnées GPS invalides.', 'error'); return; }
  if (!inTunisia(lat, lon)) {
    mapMessage(origin === 'locate' ? 'Votre position actuelle n\'est pas en Tunisie : choisissez l\'emplacement sur la carte.'
      : 'Cette position est en dehors de la Tunisie : choisissez un emplacement sur le territoire tunisien.', 'error');
    if (geo.marker && Number.isFinite(prev[0]) && inTunisia(prev[0], prev[1])) geo.marker.setLatLng(prev);   // on revient à la position valide
    return;
  }
  lat = +lat.toFixed(5); lon = +lon.toFixed(5);
  $('fLat').value = lat; $('fLon').value = lon;
  ['fLat', 'fLon'].forEach((id) => $(id).dispatchEvent(new Event('input', { bubbles: true })));   // efface une erreur affichée
  mapMessage(''); setMapStatus(true);
  placeMarker(lat, lon);
  if (geo.map && (origin === 'search' || origin === 'locate' || origin === 'typed')) geo.map.setView([lat, lon], Math.max(geo.map.getZoom(), 11));
  reverseGeocode(lat, lon);
}

function mapSetReadonly(on) {
  geo.readonly = on;
  if (geo.marker) { if (on) geo.marker.dragging.disable(); else geo.marker.dragging.enable(); }
  ['mapSearch', 'btnMapSearch', 'btnMapLocate', 'btnMapReset'].forEach((id) => { if ($(id)) $(id).disabled = on; });
  if ($('farmMap')) $('farmMap').classList.toggle('readonly', on);
}

function resetLocation() {
  mapMessage('');
  const farm = state.farms.find((f) => f.id === state.farmId);
  if (state.farmMode === 'add') mapClear();
  else if (farm && state.farmMode === 'edit') { $('fLat').value = farm.lat; $('fLon').value = farm.lon; mapShow(farm.lat, farm.lon); }
  else if (defaultConfig) { $('fLat').value = defaultConfig.lat; $('fLon').value = defaultConfig.lon; mapShow(defaultConfig.lat, defaultConfig.lon); }
}

function locateMe() {
  if (!navigator.geolocation) { mapMessage('La géolocalisation n\'est pas disponible dans ce navigateur : cliquez sur la carte.'); return; }
  mapMessage('Recherche de votre position… (autorisez la demande du navigateur)');
  navigator.geolocation.getCurrentPosition(
    (pos) => selectLocation(pos.coords.latitude, pos.coords.longitude, 'locate'),
    (err) => mapMessage(err.code === 1
      ? 'Géolocalisation refusée : vous pouvez cliquer sur la carte ou rechercher votre ville.'
      : 'Position introuvable pour le moment : cliquez sur la carte ou recherchez votre ville.'),
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
}

function showSuggestions(items, note) {
  const box = $('mapSuggest');
  box.innerHTML = items.map((it, i) => `<button type="button" role="option" data-i="${i}">${esc(it.label)}${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</button>`).join('')
    + (note ? `<div class="map-suggest-note">${esc(note)}</div>` : '');
  box.hidden = !items.length && !note;
  box.onclick = (e) => {
    const b = e.target.closest('button[data-i]'); if (!b) return;
    const it = items[+b.dataset.i];
    box.hidden = true; $('mapSearch').value = it.label;
    selectLocation(it.lat, it.lon, 'search');
  };
}

function localSuggestions() {                    // pendant la frappe : aucune requête réseau
  const q = plain($('mapSearch').value.trim());
  if (q.length < 2) { $('mapSuggest').hidden = true; return; }
  const items = TN_PLACES.filter(([n]) => plain(n).includes(q)).slice(0, 5)
    .map(([n, lat, lon]) => ({ label: n, sub: 'Chef-lieu de gouvernorat', lat, lon }));
  showSuggestions(items, 'Entrée ou « Rechercher » : chercher « ' + $('mapSearch').value.trim() + ' » sur OpenStreetMap');
}

async function searchPlace() {                   // recherche explicite (Entrée / bouton), restreinte à la Tunisie
  const q = $('mapSearch').value.trim();
  if (q.length < 2) { mapMessage('Saisissez au moins 2 caractères.'); return; }
  showSuggestions([], 'Recherche en cours…');
  try {
    const res = await nominatim(`/search?format=jsonv2&countrycodes=tn&limit=6&addressdetails=1&accept-language=fr`
      + `&viewbox=7,38.5,12,30&bounded=1&q=${encodeURIComponent(q)}`);
    const items = (res || []).map((r) => ({ label: r.name || r.display_name.split(',')[0], sub: placeName(r.address) || r.display_name, lat: +r.lat, lon: +r.lon }))
      .filter((it) => inTunisia(it.lat, it.lon));
    if (!items.length) { showSuggestions([], `Aucun lieu trouvé en Tunisie pour « ${q} ».`); return; }
    showSuggestions(items, null);
  } catch (_) {
    const local = TN_PLACES.filter(([n]) => plain(n).includes(plain(q))).map(([n, lat, lon]) => ({ label: n, sub: 'Chef-lieu de gouvernorat', lat, lon }));
    showSuggestions(local, 'Service de recherche indisponible (connexion internet ?). Cliquez directement sur la carte.');
  }
}

function initMap() {
  const el = $('farmMap'); if (!el) return;
  ['fLat', 'fLon'].forEach((id) => $(id).addEventListener('change', () => {
    const lat = parseFloat($('fLat').value), lon = parseFloat($('fLon').value);
    if (Number.isFinite(lat) && Number.isFinite(lon)) selectLocation(lat, lon, 'typed');
  }));
  if (state.view !== 'settings') return;                 // la carte ne s'affiche que sur la page Paramètres
  if (typeof L === 'undefined') {
    mapMessage('Carte indisponible (bibliothèque non chargée) : saisissez la latitude et la longitude ci-dessous.');
    return;
  }
  geo.map = L.map(el, { maxBounds: L.latLngBounds([29.4, 6.3], [38.9, 12.7]), maxBoundsViscosity: 1, minZoom: 6, maxZoom: 18 })
    .setView([34.0, 9.6], 6);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).on('tileerror', () => {
    if (geo.tileWarned) return; geo.tileWarned = true;
    mapMessage('Fond de carte indisponible (pas de connexion internet ?). Vous pouvez saisir la latitude et la longitude ci-dessous.');
  }).addTo(geo.map);
  try {   // contour indicatif (dessin vectoriel) : la carte reste utilisable même si le navigateur ne sait pas le tracer
    L.polygon(TN_OUTLINE, { color: COLORS.green, weight: 1.5, dashArray: '5 5', fillOpacity: 0.05, interactive: false }).addTo(geo.map);
  } catch (_) { /* rendu SVG/canvas indisponible */ }
  geo.map.on('click', (e) => selectLocation(e.latlng.lat, e.latlng.lng, 'click'));
  $('mapSearch').addEventListener('input', localSuggestions);
  $('mapSearch').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); searchPlace(); } if (e.key === 'Escape') $('mapSuggest').hidden = true; });
  $('btnMapSearch').addEventListener('click', searchPlace);
  $('btnMapLocate').addEventListener('click', locateMe);
  $('btnMapReset').addEventListener('click', resetLocation);
  document.addEventListener('click', (e) => { if (!e.target.closest('.map-search')) $('mapSuggest').hidden = true; });
  setTimeout(() => geo.map.invalidateSize(), 250);
}

/** Indique d'où viennent les prévisions (météo réelle Open-Meteo, ou prévisions du jour en cache). */
function renderWeatherSource(plan) {
  if (!plan || !plan.weatherSource) return;
  const where = Number.isFinite(plan.lat) ? `${nf2.format(plan.lat)}°, ${nf2.format(plan.lon)}°` : '';
  const txt = (plan.weatherSource === 'cache' ? 'Prévisions Open-Meteo du jour (en cache, service hors ligne)' : 'Météo réelle Open-Meteo')
    + (where ? ' · ' + where : '') + ' · risque de coupure estimé (modèle proxy)';
  if ($('heroWeather')) $('heroWeather').textContent = txt;
}

/* ---------- 9 bis. Mes fermes (CRUD /api/farms, propre à l'utilisateur connecté) ---------- */
// Le propriétaire n'est JAMAIS envoyé : le serveur l'identifie par la session. La ferme sélectionnée est
// mémorisée dans l'onglet (sessionStorage) avec l'e-mail du compte, puis revalidée auprès du serveur.
const FARM_KEY = 'agriwatt.selectedFarm';
const FARM_INTENT_KEY = 'agriwatt.farmIntent';
const CROP_LABELS = { olive: 'Oliviers', datte: 'Palmiers dattiers', tomate: 'Tomates', ble: 'Blé' };
const FARM_FIELDS = {            // champ JSON de l'API -> champ du formulaire existant
  name: 'fName', lat: 'fLat', lon: 'fLon', crop: 'fCrop', areaM2: 'fArea', panelAreaM2: 'fPanel',
  panelEfficiency: 'fEff', pumpPowerKw: 'fPump', pumpFlowM3h: 'fFlow', tankCapacityM3: 'fTank',
  tankInitialM3: 'fTankInit', outageStartHour: 'fOutStart', outageEndHour: 'fOutEnd', days: 'fDays',
  offPeakTariff: 'fTOff', normalTariff: 'fTNorm', peakTariff: 'fTPeak',
};
const FARM_LOCKABLE = ['fName', 'fLat', 'fLon', 'fCrop', 'fArea', 'fPump', 'fFlow', 'fPanel', 'fTank', 'fTankInit',
  'fEff', 'fDays', 'fTOff', 'fTNorm', 'fTPeak', 'fOutageOn', 'fOutStart', 'fOutEnd'];
let defaultConfig = null;        // config par défaut du serveur, pour « Nouvelle ferme » et le retour à l'état initial
let toastTimer = null;

function clearUserSession() {
  ['agriwatt.selectedFarm', 'agriwatt.farmIntent', 'agriwatt.pendingConfig'].forEach((k) => {
    try { sessionStorage.removeItem(k); } catch (_) { /* stockage indisponible */ }
  });
}
function readSession(key) { try { return JSON.parse(sessionStorage.getItem(key) || 'null'); } catch (_) { return null; } }
function writeSession(key, value) { try { sessionStorage.setItem(key, JSON.stringify(value)); } catch (_) { /* ignoré */ } }

function notify(message, kind = 'ok') {
  const t = $('farmToast'); if (!t) return;
  t.textContent = message; t.classList.toggle('error', kind === 'error'); t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}

/** Appel /api/farms : gère 204, les erreurs JSON {message, errors} et le jeton CSRF. */
async function farmApi(method, path, body) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const csrf = document.cookie.split('; ').find((p) => p.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
    if (csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf);
  }
  const r = await fetch(API + path, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  const data = r.status === 204 ? null : await r.json().catch(() => null);
  if (r.status === 401) { clearUserSession(); location.assign('/login.html'); throw new Error('Session expirée'); }
  if (!r.ok) { const e = new Error((data && data.message) || 'HTTP ' + r.status); e.status = r.status; e.errors = (data && data.errors) || {}; throw e; }
  return data;
}

/* --- formulaire existant <-> ferme --- */
function farmToForm(f) {
  $('fName').value = f.name || '';
  $('fLat').value = f.lat; $('fLon').value = f.lon; $('fCrop').value = f.crop;
  mapShow(f.lat, f.lon);
  $('fArea').value = +(f.areaM2 / 10000).toFixed(4);
  $('fPump').value = f.pumpPowerKw; $('fFlow').value = f.pumpFlowM3h; $('fPanel').value = f.panelAreaM2;
  $('fEff').value = f.panelEfficiency; $('fTank').value = f.tankCapacityM3; $('fTankInit').value = f.tankInitialM3;
  $('fDays').value = f.days;
  $('fTOff').value = f.offPeakTariff; $('fTNorm').value = f.normalTariff; $('fTPeak').value = f.peakTariff;
  const on = f.outageStartHour != null && f.outageEndHour != null;
  $('fOutageOn').checked = on;
  if (on) { $('fOutStart').value = f.outageStartHour; $('fOutEnd').value = f.outageEndHour; }
  $('outageHours').classList.toggle('off', !on);
  state.tariffs = { offPeak: f.offPeakTariff, normal: f.normalTariff, peak: f.peakTariff };
}

function formToFarm() {
  const num = (id) => { const v = $(id).value.trim(); return v === '' ? null : Number(v); };
  const on = $('fOutageOn').checked;
  const ha = num('fArea');
  return {
    name: $('fName').value.trim(), lat: num('fLat'), lon: num('fLon'), crop: $('fCrop').value,
    areaM2: ha == null ? null : Math.round(ha * 10000), panelAreaM2: num('fPanel'), panelEfficiency: num('fEff'),
    pumpPowerKw: num('fPump'), pumpFlowM3h: num('fFlow'), tankCapacityM3: num('fTank'), tankInitialM3: num('fTankInit'),
    outageStartHour: on ? num('fOutStart') : null, outageEndHour: on ? num('fOutEnd') : null, days: num('fDays'),
    offPeakTariff: num('fTOff'), normalTariff: num('fTNorm'), peakTariff: num('fTPeak'),
  };
}

/** Mêmes règles que le serveur (le serveur reste l'autorité). */
function validateFarm(f) {
  const e = {};
  const ok = (v) => typeof v === 'number' && Number.isFinite(v);
  if (!f.name) e.name = 'Le nom de la ferme est obligatoire.';
  if (!ok(f.lat) || !ok(f.lon)) e.lat = 'Sélectionnez l\'emplacement de la ferme sur la carte.';
  else if (!inTunisia(f.lat, f.lon)) e.lat = 'La position sélectionnée n\'est pas située en Tunisie.';
  if (!CROP_LABELS[f.crop]) e.crop = 'Culture invalide.';
  if (!ok(f.areaM2) || f.areaM2 <= 0) e.areaM2 = 'La surface agricole doit être strictement positive.';
  if (!ok(f.panelAreaM2) || f.panelAreaM2 < 0) e.panelAreaM2 = 'La surface des panneaux doit être positive ou nulle.';
  if (!ok(f.panelEfficiency) || f.panelEfficiency <= 0 || f.panelEfficiency > 1) e.panelEfficiency = 'Le rendement doit être compris entre 0 et 1.';
  if (!ok(f.pumpPowerKw) || f.pumpPowerKw <= 0) e.pumpPowerKw = 'La puissance de la pompe doit être strictement positive.';
  if (!ok(f.pumpFlowM3h) || f.pumpFlowM3h <= 0) e.pumpFlowM3h = 'Le débit de la pompe doit être strictement positif.';
  if (!ok(f.tankCapacityM3) || f.tankCapacityM3 <= 0) e.tankCapacityM3 = 'La capacité du réservoir doit être strictement positive.';
  if (!ok(f.tankInitialM3) || f.tankInitialM3 < 0 || (ok(f.tankCapacityM3) && f.tankInitialM3 > f.tankCapacityM3)) e.tankInitialM3 = 'Le niveau initial doit être compris entre 0 et la capacité du réservoir.';
  if (f.outageStartHour != null || f.outageEndHour != null) {
    if (!Number.isInteger(f.outageStartHour) || f.outageStartHour < 0 || f.outageStartHour > 23) e.outageStartHour = 'Heure de début entre 0 et 23.';
    if (!Number.isInteger(f.outageEndHour) || f.outageEndHour < 0 || f.outageEndHour > 23) e.outageEndHour = 'Heure de fin entre 0 et 23.';
    else if (!e.outageStartHour && f.outageEndHour <= f.outageStartHour) e.outageEndHour = 'L\'heure de fin doit être après l\'heure de début.';
  }
  if (!Number.isInteger(f.days) || f.days < 1 || f.days > 7) e.days = 'Le nombre de jours doit être compris entre 1 et 7.';
  ['offPeakTariff', 'normalTariff', 'peakTariff'].forEach((k) => { if (!ok(f[k]) || f[k] < 0) e[k] = 'Le tarif doit être positif ou nul.'; });
  return e;
}

function clearFieldErrors() {
  document.querySelectorAll('#cfgForm .field-error').forEach((n) => n.remove());
  document.querySelectorAll('#cfgForm .is-invalid').forEach((n) => n.classList.remove('is-invalid'));
}
function showFieldErrors(errors) {
  clearFieldErrors();
  Object.entries(errors).forEach(([field, msg]) => {
    const input = $(FARM_FIELDS[field]); if (!input) return;
    input.classList.add('is-invalid');
    const small = document.createElement('small'); small.className = 'field-error'; small.textContent = msg;
    input.insertAdjacentElement('afterend', small);
  });
  const first = document.querySelector('#cfgForm .is-invalid'); if (first) first.focus();
}

/* --- modes du formulaire : libre | ajout | consultation | modification --- */
function setFarmMode(mode) {
  state.farmMode = mode;
  const farm = state.farms.find((f) => f.id === state.farmId);
  const badge = $('farmMode');
  badge.className = 'farm-mode' + (mode === 'edit' ? ' edit' : mode === 'add' ? ' add' : '');
  badge.textContent = { free: 'Aucune ferme sélectionnée', add: 'Nouvelle ferme', view: 'Consultation : ' + (farm ? farm.name : ''), edit: 'Modification : ' + (farm ? farm.name : '') }[mode];
  document.body.classList.toggle('farm-form-open', mode !== 'free');
  FARM_LOCKABLE.forEach((id) => { $(id).disabled = mode === 'view' || mode === 'free'; });
  mapSetReadonly(mode === 'view' || mode === 'free');
  $('farmActions').hidden = mode === 'free';
  $('btnFarmSave').hidden = !(mode === 'add' || mode === 'edit');
  $('btnFarmSave').textContent = mode === 'add' ? 'Enregistrer la ferme' : 'Enregistrer les modifications';
  $('btnFarmEdit').hidden = mode !== 'view';
  $('btnFarmCancel').hidden = !(mode === 'add' || mode === 'edit');
  if (mode !== 'add' && mode !== 'edit') clearFieldErrors();
  updateComputeAvailability();
}

function updateFarmLabel() {
  const farm = state.farms.find((f) => f.id === state.farmId);
  const label = $('farmLabel'); if (!label) return;
  const icon = label.querySelector('svg');
  label.textContent = farm
    ? ` ${farm.name} · ${nf2.format(farm.lat)}°, ${nf2.format(farm.lon)}° · ${nf1.format(farm.areaM2 / 10000)} ha · ${CROP_LABELS[farm.crop] || farm.crop}`
    : ' Aucune ferme sélectionnée · gérez vos fermes dans Paramètres';
  if (icon) label.prepend(icon);
}

/* --- liste « Mes fermes » --- */
function renderFarms() {
  const list = $('farmList'); if (!list) return;
  $('farmEmpty').hidden = state.farms.length > 0;
  list.innerHTML = state.farms.map((f) => {
    const sel = f.id === state.farmId;
    const kwc = f.panelAreaM2 * f.panelEfficiency;
    const pct = f.tankCapacityM3 > 0 ? Math.round(100 * f.tankInitialM3 / f.tankCapacityM3) : 0;
    return `<article class="farm-item${sel ? ' selected' : ''}" data-farm-id="${f.id}">
      <div class="farm-head"><div><h4>${esc(f.name)}</h4><div class="farm-loc">📍 ${nf2.format(f.lat)}°, ${nf2.format(f.lon)}°</div></div>${sel ? '<span class="farm-badge">✓ Sélectionnée</span>' : ''}</div>
      <dl class="farm-facts">
        <div><dt>Culture</dt><dd>${esc(CROP_LABELS[f.crop] || f.crop)}</dd></div>
        <div><dt>Superficie</dt><dd>${nf1.format(f.areaM2 / 10000)} ha</dd></div>
        <div><dt>Panneaux</dt><dd>${nf1.format(kwc)} kWc · ${nf0.format(f.panelAreaM2)} m²</dd></div>
        <div><dt>Pompe</dt><dd>${nf1.format(f.pumpPowerKw)} kW</dd></div>
        <div style="grid-column:1/-1"><dt>Réservoir</dt><dd>${nf1.format(f.tankInitialM3)} / ${nf1.format(f.tankCapacityM3)} m³</dd><div class="farm-tank"><i style="width:${Math.max(0, Math.min(100, pct))}%"></i></div></div>
      </dl>
      <div class="farm-btns">
        <button type="button" class="btn btn-success" data-farm-act="select" ${sel ? 'disabled' : ''}>${sel ? 'Sélectionnée' : 'Sélectionner'}</button>
        <button type="button" class="btn btn-outline-dark" data-farm-act="view">Consulter</button>
        <button type="button" class="btn btn-outline-dark" data-farm-act="edit">Modifier</button>
        <button type="button" class="btn btn-outline-danger" data-farm-act="delete">Supprimer</button>
      </div>
    </article>`;
  }).join('');
}

function farmsUnavailable(message) {
  $('farmList').innerHTML = `<p class="farm-off">${esc(message)}</p>`;
  $('farmEmpty').hidden = true;
  $('btnFarmAdd').hidden = true;
}

async function loadFarms() {
  state.farms = await farmApi('GET', '/api/farms');
  if (state.farmId != null && !state.farms.some((f) => f.id === state.farmId)) state.farmId = null;
  renderFarms();
}

/** Sélectionne une ferme (revalidée par le serveur) et charge ses paramètres dans le formulaire existant. */
async function selectFarm(id, mode = 'view', { simulate = false } = {}) {
  const farm = await farmApi('GET', '/api/farms/' + id);          // 404 si elle n'appartient pas à ce compte
  const i = state.farms.findIndex((f) => f.id === farm.id);
  if (i >= 0) state.farms[i] = farm; else state.farms.push(farm);
  state.farmId = farm.id;
  writeSession(FARM_KEY, { owner: state.me.email, id: farm.id });
  farmToForm(farm);
  setFarmMode(mode);
  renderFarms();
  updateFarmLabel();
  if (simulate) await compute();
}

function resetToInitial() {
  state.farmId = null; state.plan = null;
  try { sessionStorage.removeItem(FARM_KEY); } catch (_) { /* ignoré */ }
  if (defaultConfig) applyConfigToForm(defaultConfig);
  $('fName').value = '';
  mapClear();
  delete document.body.dataset.planGenerated;
  setFarmMode('free');
  renderFarms();
  updateFarmLabel();
  if (CALC_VIEWS.includes(state.view)) showNoFarm(true);
}

function startAddFarm() {
  if (state.view !== 'settings') { goToSettings('add'); return; }
  state.prevFarmId = state.farmId;
  state.farmId = null;
  try { sessionStorage.removeItem(FARM_KEY); } catch (_) { /* ignoré */ }
  if (defaultConfig) applyConfigToForm(defaultConfig);
  $('fName').value = '';
  mapClear();                                   // la position doit être choisie par l'utilisateur
  setFarmMode('add');
  renderFarms();
  $('sec-config').scrollIntoView({ behavior: 'smooth', block: 'start' });
  $('fName').focus();
}

function goToSettings(action, id) {      // le formulaire existant vit sur la page Paramètres
  writeSession(FARM_INTENT_KEY, { owner: state.me.email, action, id: id ?? null });
  location.assign('settings.html');
}

async function saveFarm() {
  const data = formToFarm();
  const errors = validateFarm(data);
  if (Object.keys(errors).length) { showFieldErrors(errors); notify('Corrigez les champs signalés.', 'error'); return; }
  const btn = $('btnFarmSave'); btn.disabled = true;
  try {
    const editing = state.farmMode === 'edit' && state.farmId != null;
    const saved = await farmApi(editing ? 'PUT' : 'POST', editing ? '/api/farms/' + state.farmId : '/api/farms', data);
    clearFieldErrors();
    await loadFarms();
    await selectFarm(saved.id, 'view');
    notify(editing ? 'Modifications enregistrées.' : 'Ferme « ' + saved.name + ' » enregistrée.');
  } catch (e) {
    if (e.status === 400) { showFieldErrors(e.errors); notify(e.message, 'error'); }
    else if (e.status === 404) { notify('Cette ferme n\'existe plus.', 'error'); await loadFarms(); resetToInitial(); }
    else notify('Enregistrement impossible : ' + e.message, 'error');
  } finally { btn.disabled = false; }
}

async function deleteFarm(id) {
  const farm = state.farms.find((f) => f.id === id); if (!farm) return;
  if (!window.confirm(`Supprimer définitivement la ferme « ${farm.name} » ainsi que son historique de calculs et ses mesures importées ?`)) return;
  try {
    await farmApi('DELETE', '/api/farms/' + id);
    const wasSelected = state.farmId === id;
    await loadFarms();
    notify('Ferme « ' + farm.name + ' » supprimée.');
    if (wasSelected) {
      if (state.farms.length) await selectFarm(state.farms[0].id, 'view', { simulate: CALC_VIEWS.includes(state.view) });
      else resetToInitial();
    }
  } catch (e) {
    notify('Suppression impossible : ' + e.message, 'error');
    await loadFarms().catch(() => {});
  }
}

async function onFarmAction(event) {
  const btn = event.target.closest('[data-farm-act]'); if (!btn) return;
  const id = Number(btn.closest('[data-farm-id]').dataset.farmId);
  const act = btn.dataset.farmAct;
  try {
    if (act === 'delete') return await deleteFarm(id);
    if (act === 'select') { await selectFarm(id, 'view', { simulate: CALC_VIEWS.includes(state.view) }); notify('Ferme active : ' + state.farms.find((f) => f.id === id).name); return; }
    if (state.view !== 'settings') return goToSettings(act, id);
    await selectFarm(id, act);
    $('sec-config').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) {
    notify(e.status === 404 ? 'Ferme introuvable.' : 'Action impossible : ' + e.message, 'error');
    await loadFarms().catch(() => {});
  }
}

function bindFarmEvents() {
  $('btnFarmAdd').addEventListener('click', startAddFarm);
  $('farmList').addEventListener('click', onFarmAction);
  $('btnFarmSave').addEventListener('click', saveFarm);
  $('btnFarmEdit').addEventListener('click', () => setFarmMode('edit'));
  $('btnFarmCancel').addEventListener('click', async () => {
    clearFieldErrors();
    const back = state.farmId ?? state.prevFarmId;
    if (back != null) await selectFarm(back, 'view').catch(() => resetToInitial());
    else resetToInitial();
  });
  $('cfgForm').addEventListener('input', (event) => {
    if (!event.target.classList.contains('is-invalid')) return;
    event.target.classList.remove('is-invalid');
    event.target.parentElement.querySelector('.field-error')?.remove();
  });
}

/** À l'ouverture : identifie le compte, charge SES fermes, restaure (et revalide) la ferme active de l'onglet. */
async function initFarms() {
  defaultConfig = readConfig().cfg;
  setFarmMode('free');
  try {
    state.me = await fetchJson(API + '/api/auth/me');
    await loadFarms();
  } catch (e) {
    farmsUnavailable('Impossible de charger vos fermes : ' + e.message);
    showBanner('Impossible de charger vos fermes : ' + e.message, () => location.reload());
    return;
  }
  const intent = readSession(FARM_INTENT_KEY);
  try { sessionStorage.removeItem(FARM_INTENT_KEY); } catch (_) { /* ignoré */ }
  const saved = readSession(FARM_KEY);
  if (saved && saved.owner !== state.me.email) clearUserSession();     // jamais la ferme d'un autre compte
  try {
    if (intent && intent.owner === state.me.email && state.view === 'settings') {
      if (intent.action === 'add') return startAddFarm();
      if (intent.id != null) return await selectFarm(intent.id, intent.action === 'edit' ? 'edit' : 'view');
    }
    if (saved && saved.owner === state.me.email && state.farms.some((f) => f.id === saved.id)) await selectFarm(saved.id, 'view');
    else if (state.farms.length) await selectFarm(state.farms[0].id, 'view');     // ferme active par défaut
    else resetToInitial();
  } catch (e) {
    resetToInitial();                         // ferme supprimée ou refusée par le serveur
  }
  updateFarmLabel();
}

/* ---------- 9 quater. Historique des calculs (par ferme, relu en base) ---------- */
const WEATHER_LABELS = { openmeteo: 'Open-Meteo (temps réel)', cache: 'Open-Meteo (prévisions du jour en cache)' };
const hist = { runs: [], detailId: null };
const dateTimeLabel = (iso) => { const d = new Date(iso); return Number.isNaN(d.getTime()) ? String(iso || '–') : d.toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' }); };
const fmt = (v, f, unit = '') => (typeof v === 'number' && Number.isFinite(v) ? f.format(v) + unit : '–');

function fillFarmSelect(id) {
  const sel = $(id); if (!sel) return;
  sel.innerHTML = state.farms.map((f) => `<option value="${f.id}">${esc(f.name)}</option>`).join('');
  if (state.farmId != null) sel.value = String(state.farmId);
}

/** Change la ferme active depuis Historique ou Anomalies (même ferme pour toutes les pages, revalidée par le serveur). */
async function switchFarm(id) {
  const farm = await fetchJson(API + '/api/farms/' + id);
  state.farmId = farm.id;
  writeSession(FARM_KEY, { owner: state.me.email, id: farm.id });
  updateFarmLabel();
}

async function initHistory() {
  if (!state.farms.length) { $('histEmpty').textContent = 'Aucune ferme : créez votre première ferme dans Paramètres pour enregistrer des calculs.'; $('histEmpty').hidden = false; $('histTools').hidden = true; return; }
  fillFarmSelect('histFarm');
  $('histFarm').addEventListener('change', async (e) => {
    try { await switchFarm(+e.target.value); closeRunDetail(); await loadRuns(); }
    catch (err) { showBanner('Impossible de changer de ferme : ' + err.message); }
  });
  ['histFrom', 'histTo'].forEach((id) => $(id).addEventListener('change', loadRuns));
  $('histReset').addEventListener('click', () => { $('histFrom').value = ''; $('histTo').value = ''; loadRuns(); });
  $('histBody').addEventListener('click', (e) => { const b = e.target.closest('[data-run-id]'); if (b) showRunDetail(+b.dataset.runId); });
  $('histClose').addEventListener('click', closeRunDetail);
  await loadRuns();
}

async function loadRuns() {
  const from = $('histFrom').value, to = $('histTo').value;
  if (from && to && to < from) { $('histMsg').textContent = 'La date de fin doit être après la date de début.'; return; }
  const q = new URLSearchParams(); if (from) q.set('from', from); if (to) q.set('to', to);
  $('histMsg').textContent = 'Chargement de l\'historique…';
  try {
    hist.runs = await fetchJson(API + `/api/farms/${state.farmId}/runs` + (q.toString() ? '?' + q : ''));
    hideBanner();
  } catch (e) {
    $('histMsg').textContent = '';
    showBanner('Historique indisponible : ' + e.message, loadRuns);
    return;
  }
  const farm = state.farms.find((f) => f.id === state.farmId);
  $('histTableWrap').hidden = hist.runs.length === 0;
  $('histMsg').textContent = hist.runs.length
    ? `${hist.runs.length} calcul(s) enregistré(s) pour « ${farm ? farm.name : ''} »${from || to ? ' sur la période choisie' : ''}.`
    : (from || to ? 'Aucun calcul sur cette période pour cette ferme.' : 'Aucun calcul n\'a encore été effectué pour cette ferme : lancez « Calculer le plan » depuis le tableau de bord.');
  $('histBody').innerHTML = hist.runs.map((r) => `<tr>
      <td><b>${esc(dateTimeLabel(r.createdAt))}</b><small class="d-block text-muted">#${r.id}</small></td>
      <td>${r.forecastStart ? esc(timeLabel(r.forecastStart)) + ' → ' + esc(timeLabel(r.forecastEnd)) : '–'}</td>
      <td class="num">${fmt(r.solarKwh, nf1, ' kWh')}</td>
      <td class="num">${fmt(r.waterDeliveredM3, nf0, '')} / ${fmt(r.waterNeedM3, nf0, ' m³')}</td>
      <td class="num">${fmt(r.pumpEnergyKwh, nf1, ' kWh')}</td>
      <td class="num">${fmt(r.solarSharePct, nf0, ' %')}</td>
      <td class="num"><b>${fmt(r.savingsTnd, nf2, ' TND')}</b></td>
      <td class="num">${fmt(r.minTankLevelM3, nf1, '')} / ${fmt(r.finalTankLevelM3, nf1, ' m³')}</td>
      <td><button type="button" class="btn btn-sm btn-outline-dark fw-semibold" data-run-id="${r.id}">Voir les détails</button></td>
    </tr>`).join('');
}

function closeRunDetail() { hist.detailId = null; $('histDetail').hidden = true; }

/** Détail d'un ancien calcul : tout est relu en base, aucune optimisation n'est relancée. */
async function showRunDetail(id) {
  let d;
  try { d = await fetchJson(API + '/api/runs/' + id); }
  catch (e) { showBanner('Détail indisponible : ' + e.message, () => showRunDetail(id)); return; }
  hist.detailId = id;
  const r = d.summary, k = d.kpis || {}, cfg = d.config || { tankCapacityM3: 0, pumpPowerKw: 5 };
  $('histDetailTitle').textContent = `Calcul #${r.id} du ${dateTimeLabel(r.createdAt)} · ${r.farmName || ''}`;
  $('histDetailMeta').textContent = [
    Number.isFinite(r.lat) ? `${nf2.format(r.lat)}°, ${nf2.format(r.lon)}°` : null,
    r.crop ? CROP_LABELS[r.crop] || r.crop : null,
    Number.isFinite(r.areaM2) ? nf1.format(r.areaM2 / 10000) + ' ha' : null,
    r.forecastStart ? `prévision ${timeLabel(r.forecastStart)} → ${timeLabel(r.forecastEnd)}` : null,
    r.weatherSource ? 'météo ' + (WEATHER_LABELS[r.weatherSource] || r.weatherSource) : null,
  ].filter(Boolean).join(' · ');
  const tiles = [
    ['Énergie solaire utilisée', fmt(k.solarSharePct, nf0, ' %')],
    ['Économies', fmt(k.savingsTnd, nf2, ' TND')],
    ['Coût avec / sans AgriWatt', `${fmt(k.costOptimizedTnd, nf2)} / ${fmt(k.costBaselineTnd, nf2, ' TND')}`],
    ['Réseau évité en pointe', fmt(k.gridPeakKwhAvoided, nf1, ' kWh')],
    ['Coupure couverte', `${fmt(k.outageHoursCovered, nf0, ' h')} (sans : ${fmt(k.baselineOutageHoursCovered, nf0, ' h')})`],
    ['Production solaire utilisée', fmt(r.solarKwh, nf1, ' kWh')],
    ['Énergie de la pompe', fmt(r.pumpEnergyKwh, nf1, ' kWh')],
    ['Eau livrée / besoin', `${fmt(r.waterDeliveredM3, nf0)} / ${fmt(r.waterNeedM3, nf0, ' m³')}`],
    ['Réservoir min / final', `${fmt(r.minTankLevelM3, nf1)} / ${fmt(r.finalTankLevelM3, nf1, ' m³')}`],
  ];
  $('histDetailKpis').innerHTML = tiles.map(([l, v]) => `<div class="hist-kpi"><small>${esc(l)}</small><b>${esc(v)}</b></div>`).join('');
  $('histDetail').hidden = false;
  const plan = { schedule: d.schedule, baselineSchedule: d.baselineSchedule, kpis: k };
  if (d.schedule && d.schedule.length) {
    renderCharts(plan, cfg, { tank: 'chHistTank', pump: 'chHistPump', solar: 'chHistSolar' });
    renderTable(plan, cfg, 'histPlanBody', 'all');
  }
  $('histDetail').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/* ---------- 9 quinquies. Anomalies : mesures réelles de la pompe importées (CSV) ---------- */
const anom = { data: null };

/** Lit un CSV « horodatage ; puissance (kW) » (séparateur , ou ; ; en-tête facultatif ; décimale , ou .). */
function parsePumpCsv(text) {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const points = [], errors = [];
  lines.forEach((line, n) => {
    const sep = line.includes(';') ? ';' : ',';
    const cols = line.split(sep).map((c) => c.trim().replace(/^"|"$/g, ''));
    if (cols.length < 2) { errors.push(`ligne ${n + 1} : 2 colonnes attendues`); return; }
    let ts = cols[0].replace(' ', 'T');
    const m = /^(\d{2})\/(\d{2})\/(\d{4})[T ](\d{2}):(\d{2})/.exec(cols[0]);
    if (m) ts = `${m[3]}-${m[2]}-${m[1]}T${m[4]}:${m[5]}`;
    const kw = Number(cols[1].replace(',', '.'));
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(ts) || !Number.isFinite(kw)) {
      if (n === 0) return;                        // ligne d'en-tête
      errors.push(`ligne ${n + 1} : « ${line.slice(0, 40)} »`); return;
    }
    if (kw < 0) { errors.push(`ligne ${n + 1} : puissance négative`); return; }
    points.push({ timestamp: ts.slice(0, 16), powerKw: kw });
  });
  return { points, errors };
}

function renderAnomalyState() {
  const d = anom.data, has = d && d.count > 0;
  $('anomEmpty').hidden = has;
  $('sec-anom').hidden = !has;
  $('btnAnomClear').hidden = !has;
  $('anomInfo').textContent = has
    ? `${d.count} mesures${d.fileName ? ' · fichier « ' + d.fileName + ' »' : ''}${d.importedAt ? ' · importé le ' + dateTimeLabel(d.importedAt) : ''} · ${d.anomalyCount} anomalie(s) détectée(s)`
    : '';
  if (has) renderAnomalyChart(d);
}

async function loadFarmAnomalies() {
  try { anom.data = await fetchJson(API + `/api/farms/${state.farmId}/anomalies`); hideBanner(); }
  catch (e) { anom.data = null; showBanner('Mesures indisponibles : ' + e.message, loadFarmAnomalies); }
  renderAnomalyState();
}

async function importPumpFile() {
  const file = $('anomFile').files[0];
  if (!file) { $('anomMsg').textContent = 'Choisissez d\'abord un fichier CSV.'; return; }
  const { points, errors } = parsePumpCsv(await file.text());
  if (errors.length) { $('anomMsg').textContent = `Fichier refusé (${errors.length} ligne(s) invalide(s)) : ${errors.slice(0, 3).join(' ; ')}`; return; }
  if (points.length < 20) { $('anomMsg').textContent = 'Au moins 20 mesures sont nécessaires pour analyser la pompe.'; return; }
  $('anomMsg').textContent = `Analyse de ${points.length} mesures en cours…`;
  $('btnAnomImport').disabled = true;
  try {
    anom.data = await fetchJson(API + `/api/farms/${state.farmId}/anomalies`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ fileName: file.name, points }),
    }, 60000);
    $('anomMsg').textContent = '';
    notify(`${anom.data.count} mesures analysées : ${anom.data.anomalyCount} anomalie(s).`);
    renderAnomalyState();
  } catch (e) {
    $('anomMsg').textContent = 'Import impossible : ' + e.message;
  } finally { $('btnAnomImport').disabled = false; }
}

async function initAnomalies() {
  if (!state.farms.length) { $('anomEmpty').textContent = 'Aucune ferme : créez une ferme dans Paramètres, puis importez les mesures de sa pompe.'; $('anomEmpty').hidden = false; $('anomTools').hidden = true; $('sec-anom').hidden = true; return; }
  fillFarmSelect('anomFarm');
  $('anomFarm').addEventListener('change', async (e) => {
    try { await switchFarm(+e.target.value); await loadFarmAnomalies(); }
    catch (err) { showBanner('Impossible de changer de ferme : ' + err.message); }
  });
  $('btnAnomImport').addEventListener('click', importPumpFile);
  $('btnAnomClear').addEventListener('click', async () => {
    if (!window.confirm('Supprimer les mesures importées et les anomalies de cette ferme ?')) return;
    try { await fetchJson(API + `/api/farms/${state.farmId}/anomalies`, { method: 'DELETE' }); anom.data = null; renderAnomalyState(); notify('Mesures supprimées.'); }
    catch (e) { notify('Suppression impossible : ' + e.message, 'error'); }
  });
  await loadFarmAnomalies();
}

/* ---------- 9 sexies. Alertes SMS de coupure (TextBee, côté serveur) ---------- */
// La clé TextBee reste sur le serveur : le navigateur ne connaît que les réglages du compte et l'historique.
const SMS_STATUS = { PENDING: 'En attente', SENT: 'Transmis à TextBee', FAILED: 'Échec', CANCELLED: 'Annulée' };
const SMS_DELIVERY = { pending: 'en file d\'attente', dispatched: 'envoyé au téléphone', sent: 'envoyé par le téléphone', delivered: 'livré ✓', failed: 'échec de livraison', unknown: 'inconnu' };

function smsMessage(text, kind = 'ok') { const m = $('smsMsg'); if (!m) return; m.textContent = text || ''; m.classList.toggle('error', kind === 'error'); }

async function loadSmsHistory() {
  try {
    const list = await fetchJson(API + '/api/account/alerts/history');
    $('smsHistoryWrap').hidden = list.length === 0;
    $('smsEmpty').hidden = list.length > 0;
    $('smsHistory').innerHTML = list.map((a) => `<tr>
        <td>${esc(dateTimeLabel(a.createdAt || a.scheduledAt))}</td>
        <td>${a.kind === 'TEST' ? 'Test' : esc(a.farmName || 'Coupure')}</td>
        <td>${a.outageStart ? esc(timeLabel(a.outageStart)) + (a.plannedOutage === false ? ' <small class="text-muted">(risque estimé)</small>' : '') : '–'}</td>
        <td>${a.scheduledAt ? esc(timeLabel(a.scheduledAt)) : '–'}</td>
        <td><span class="pill sms-${a.status.toLowerCase()}">${esc(SMS_STATUS[a.status] || a.status)}</span></td>
        <td>${a.providerStatus ? esc(SMS_DELIVERY[a.providerStatus] || a.providerStatus) : '–'}${a.smsBatchId ? `<small class="d-block text-muted">lot ${esc(a.smsBatchId)}</small>` : ''}</td>
        <td class="sms-detail">${esc(a.lastError || a.message || '')}</td>
      </tr>`).join('');
  } catch (e) { smsMessage('Historique des alertes indisponible : ' + e.message, 'error'); }
}

async function initSms() {
  if (!$('sec-sms')) return;
  try {
    const st = await fetchJson(API + '/api/account/alerts');
    $('smsPhone').value = st.phoneNumber || '';
    $('smsEnabled').checked = !!st.smsAlertsEnabled;
    $('smsLead').textContent = st.leadHours + ' h';
  } catch (e) { smsMessage('Réglages des alertes indisponibles : ' + e.message, 'error'); }
  fetchJson(API + '/api/sms/status').then((g) => {
    $('smsGateway').textContent = g.configured && g.reachable && g.enabledDevices > 0
      ? `Passerelle SMS opérationnelle${g.defaultDeviceName ? ' · ' + g.defaultDeviceName : ''}`
      : (g.message || 'Passerelle SMS indisponible');
    $('smsGateway').classList.toggle('ok', g.configured && g.reachable && g.enabledDevices > 0);
  }).catch(() => { $('smsGateway').textContent = 'Passerelle SMS : état inconnu'; });
  $('btnSmsSave').addEventListener('click', async () => {
    const phone = $('smsPhone').value.trim();
    if ($('smsEnabled').checked && !phone) { smsMessage('Indiquez un numéro de téléphone pour activer les alertes.', 'error'); return; }
    try {
      const st = await fetchJson(API + '/api/account/alerts', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phoneNumber: phone || null, smsAlertsEnabled: $('smsEnabled').checked }) });
      $('smsPhone').value = st.phoneNumber || '';
      smsMessage(st.smsAlertsEnabled ? `Alertes activées pour ${st.phoneNumber}.` : 'Alertes SMS désactivées (alertes en attente annulées).');
      loadSmsHistory();
    } catch (e) { smsMessage(e.message, 'error'); }
  });
  $('btnSmsTest').addEventListener('click', async () => {
    $('btnSmsTest').disabled = true;
    let lockMs = 0;
    try {
      const a = await fetchJson(API + '/api/account/alerts/test', { method: 'POST' }, 30000);
      if (a.status === 'SENT') {
        smsMessage(`SMS de test transmis à TextBee (lot ${a.smsBatchId}) vers ${a.recipient}. La livraison sera confirmée dans l'historique.`);
        lockMs = 60000;                                  // 1 SMS de test par minute (règle aussi appliquée par le serveur)
      } else smsMessage('SMS de test non envoyé : ' + (a.lastError || a.status), 'error');
      loadSmsHistory();
    } catch (e) { smsMessage(e.message, 'error'); }
    finally { setTimeout(() => { $('btnSmsTest').disabled = false; }, lockMs); }
  });
  await loadSmsHistory();
}

/* ---------- 10. Orchestration ---------- */
const CALC_VIEWS = ['dashboard', 'energy', 'planning'];

/** Le calcul n'est possible que pour une ferme enregistrée et sélectionnée (ses paramètres font foi). */
function updateComputeAvailability() {
  const ready = state.farmId != null && state.farmMode === 'view';
  ['btnCompute', 'btnHeroCompute'].forEach((id) => { if ($(id)) $(id).disabled = !ready; });
  const hint = $('calcHint');
  if (hint) {
    hint.textContent = state.farmMode === 'edit' || state.farmMode === 'add'
      ? 'Enregistrez la ferme pour calculer son plan.'
      : (state.farmId == null ? 'Sélectionnez ou ajoutez une ferme pour calculer son plan.' : '');
    hint.hidden = !hint.textContent;
  }
}

function showNoFarm(on) {
  const box = $('noFarm'); if (!box) return;
  box.hidden = !on;
  document.body.classList.toggle('no-farm', on);
}

async function compute() {
  if (state.farmId == null) { showNoFarm(true); return; }
  if (state.farmMode !== 'view') { updateComputeAvailability(); return; }
  const { cfg, errors } = readConfig();
  if (errors.length) { showBanner(errors.join(' ')); return; }
  hideBanner(); showNoFarm(false);
  state.cfg = cfg;
  setLoading(true);
  try {
    const plan = await loadPlan(cfg);
    state.plan = plan;
    renderWeatherSource(plan);
    if (state.view === 'dashboard') { renderHero(plan); renderKpis(plan); }
    if (state.view === 'energy') { renderTankChips(plan); renderCharts(plan); }
    if (state.view === 'planning' || state.view === 'settings') {
      if (state.view === 'settings') document.body.dataset.planGenerated = 'true';
      renderCharts(plan); renderTable();
      if (state.view === 'settings') {
        requestAnimationFrame(() => document.getElementById('sec-plan').scrollIntoView({ behavior: 'smooth', block: 'start' }));
      }
    }
  } catch (e) {
    console.error(e);
    showBanner('Calcul impossible : ' + e.message, compute);
  } finally { setLoading(false); }
}

function resizeCharts() { setTimeout(() => Object.values(state.charts).forEach((c) => c.resize()), 80); }

function bindEvents() {
  const desktopNav = document.querySelector('.nav-list');
  const mobileNav = $('mobileNav');
  const mobileToggle = $('mobileNavToggle');
  if (desktopNav && mobileNav && mobileToggle) {
    mobileNav.innerHTML = desktopNav.innerHTML;
    mobileNav.addEventListener('click', (event) => {
      if (!event.target.closest('a')) return;
      mobileNav.hidden = true;
      mobileToggle.setAttribute('aria-expanded', 'false');
    });
    mobileToggle.addEventListener('click', () => {
      mobileNav.hidden = !mobileNav.hidden;
      mobileToggle.setAttribute('aria-expanded', String(!mobileNav.hidden));
    });
  }
  document.querySelectorAll('.nav-list .nav-item[data-page-link], .mobile-nav .nav-item[data-page-link]').forEach((link) => {
    link.classList.toggle('active', link.dataset.pageLink === state.view);
  });
  document.querySelectorAll('[data-page-link="logout"]').forEach((link) => link.addEventListener('click', async (event) => {
    event.preventDefault();
    const csrf = document.cookie.split('; ').find((part) => part.startsWith('XSRF-TOKEN='))?.split('=').slice(1).join('=');
    clearUserSession();
    try {
      await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': decodeURIComponent(csrf || '') } });
    } finally { location.assign('/login.html'); }
  }));
  bindFarmEvents();
  initMap();
  $('btnCompute').addEventListener('click', compute);
  $('btnHeroCompute').addEventListener('click', compute);
  $('fOutageOn').addEventListener('change', (event) => $('outageHours').classList.toggle('off', !event.target.checked));
  $('cfgForm').addEventListener('keydown', (event) => { if (event.key === 'Enter') event.preventDefault(); });
  document.querySelectorAll('.day-tab').forEach((tab) => tab.addEventListener('click', () => {
    document.querySelectorAll('.day-tab').forEach((item) => item.classList.toggle('active', item === tab));
    state.day = tab.dataset.day; renderTable();
  }));
  $('btnFullscreen').addEventListener('click', (event) => {
    event.preventDefault();
    if (document.fullscreenElement) document.exitFullscreen(); else document.documentElement.requestFullscreen().catch(() => {});
  });
  document.addEventListener('fullscreenchange', resizeCharts);
  window.addEventListener('resize', resizeCharts);
}

async function init() {
  Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
  Chart.defaults.color = COLORS.muted;
  bindEvents();
  try {                                          // valeurs proposées pour « Nouvelle ferme »
    const cfg = await fetchJson(API + '/api/config/default', {}, 5000);
    applyConfigToForm(cfg);
  } catch (e) {
    showBanner('Serveur AgriWatt injoignable : ' + e.message, () => location.reload());
    return;
  }
  await initFarms();
  updateComputeAvailability();
  if (state.view === 'history') return initHistory();
  if (state.view === 'anomalies') return initAnomalies();
  if (state.view === 'settings') return initSms();
  if (state.view === 'detection') return typeof initDetection === 'function' ? initDetection() : undefined;   // js/detection.js
  if (CALC_VIEWS.includes(state.view)) {
    if (state.farmId == null) { showNoFarm(true); return; }
    await compute();
  }
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
else init();
window.__agriwatt = { state, compute, buildSummary, geo, selectLocation, inTunisia, parsePumpCsv }; // pratique pour déboguer dans la console
