'use strict';
(async function loadAgriWattView() {
  const script = document.currentScript;
  const view = script.dataset.view;
  document.body.dataset.view = view;
  const root = document.getElementById('appRoot');
  try {
    const response = await fetch('index.html');
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const source = await response.text();
    const parsed = new DOMParser().parseFromString(source, 'text/html');
    const app = parsed.querySelector('.app');
    if (!app) throw new Error('Gabarit AgriWatt introuvable');
    root.replaceChildren(app);
    const loadScript = (src) => new Promise((resolve, reject) => {
      const node = document.createElement('script'); node.src = src;
      node.onload = resolve; node.onerror = () => reject(new Error('Chargement impossible : ' + src));
      document.body.append(node);
    });
    await loadScript('lib/chartjs/chart.umd.min.js');
    if (view === 'settings') await loadScript('lib/leaflet/leaflet.js').catch(() => {});   // carte du formulaire
    if (view === 'detection') await loadScript('js/detection.js?v=20261122-detect');         // détection du stress hydrique

    await loadScript('js/app.js?v=20261122-detect');
    if (location.hash) requestAnimationFrame(() => document.getElementById(decodeURIComponent(location.hash.slice(1)))?.scrollIntoView());
  } catch (error) {
    root.innerHTML = '<main class="container py-5"><h1>Page indisponible</h1><p>Could not load AgriWatt interface: ' + String(error.message).replace(/[&<>"']/g, '') + '</p><a href="index.html">Retour au tableau de bord</a></main>';
  }
})();



