'use strict';

// Page de connexion / inscription : on efface les données d'interface du compte précédent dans cet onglet
// (ferme sélectionnée, etc.). Elles sont de toute façon revalidées par le serveur.
try { ['agriwatt.selectedFarm', 'agriwatt.farmIntent', 'agriwatt.pendingConfig'].forEach((k) => sessionStorage.removeItem(k)); } catch (_) { /* ignoré */ }

const googleLogin = document.getElementById('googleLogin');
const googleStatus = document.getElementById('googleStatus');
if (googleLogin) {
  fetch('/api/auth/google/status', { credentials: 'same-origin' })
    .then((response) => response.ok ? response.json() : Promise.reject())
    .then(({ configured }) => {
      if (configured) {
        googleLogin.removeAttribute('aria-disabled');
        googleLogin.removeAttribute('title');
        googleStatus?.remove();
      } else if (googleStatus) {
        googleStatus.textContent = 'Google sera disponible après l’ajout des identifiants OAuth Google dans la configuration du serveur.';
      }
    })
    .catch(() => {
      if (googleStatus) googleStatus.textContent = 'Impossible de vérifier la configuration Google pour le moment.';
    });
}

if (new URLSearchParams(location.search).has('googleError')) {
  const message = document.getElementById('loginMessage');
  if (message) message.textContent = 'La connexion Google a échoué. Réessayez ou connectez-vous par e-mail.';
}

document.querySelectorAll('[data-toggle-password]').forEach((button) => {
  button.addEventListener('click', () => {
    const input = document.getElementById(button.dataset.togglePassword);
    if (!input) return;
    const showing = input.type === 'password';
    input.type = showing ? 'text' : 'password';
    button.setAttribute('aria-label', showing ? 'Masquer le mot de passe' : 'Afficher le mot de passe');
  });
});

async function csrfToken() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
  if (!response.ok) throw new Error('Le serveur est indisponible. Vérifiez MySQL et relancez AgriWatt.');
  return (await response.json()).token;
}

async function submitAuth(path, payload) {
  const token = await csrfToken();
  const response = await fetch(path, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
    body: JSON.stringify(payload),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || 'La demande a échoué. Réessayez.');
  location.assign('/index.html');
}

const loginForm = document.getElementById('loginForm');
if (loginForm) {
  loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const message = document.getElementById('loginMessage');
    message.textContent = '';
    if (!loginForm.reportValidity()) return;
    try {
      await submitAuth('/api/auth/login', {
        email: loginForm.elements.email.value,
        password: loginForm.elements.password.value,
      });
    } catch (error) { message.textContent = error.message; }
  });
  document.getElementById('forgotLink')?.addEventListener('click', (event) => {
    event.preventDefault();
    document.getElementById('loginMessage').textContent = 'La récupération de compte n’est pas encore configurée.';
  });
}

const registerForm = document.getElementById('registerForm');
if (registerForm) {
  registerForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const message = document.getElementById('registerMessage');
    message.textContent = '';
    if (!registerForm.reportValidity()) return;
    if (registerForm.elements.password.value !== registerForm.elements.confirmPassword.value) {
      message.textContent = 'Les deux mots de passe ne correspondent pas.';
      registerForm.elements.confirmPassword.focus();
      return;
    }
    try {
      const phone = registerForm.elements.phoneNumber.value.trim();
      const smsAlerts = registerForm.elements.smsAlerts.checked;
      if (smsAlerts && !phone) {
        message.textContent = 'Indiquez votre numéro de téléphone pour recevoir les alertes SMS.';
        registerForm.elements.phoneNumber.focus();
        return;
      }
      await submitAuth('/api/auth/register', {
        fullName: registerForm.elements.fullName.value,
        email: registerForm.elements.email.value,
        password: registerForm.elements.password.value,
        phoneNumber: phone || null,
        smsAlerts,
      });
    } catch (error) { message.textContent = error.message; }
  });
}
