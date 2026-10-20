/* Fonctions partagées par les écrans SIDAM CONNECT (Store, ERP, Delivery, Control Tower). */
(function () {
  'use strict';

  /** Appel à l'API du bus. Lève une Error avec le message renvoyé par le serveur. */
  async function api(path, options = {}) {
    const opts = Object.assign({ headers: {} }, options);
    if (opts.json !== undefined) {
      opts.method = opts.method || 'POST';
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(opts.json);
      delete opts.json;
    }
    const res = await fetch(path, opts);
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('Erreur ' + res.status));
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  /**
   * Connexion temps réel aux événements du bus (WebSocket /ws/events), avec reconnexion automatique.
   * onEvent(event) est appelé pour chaque événement ; onState(true|false) quand la connexion change.
   */
  function connectLive(onEvent, onState) {
    let delay = 1000;
    function open() {
      const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws/events');
      ws.onopen = () => { delay = 1000; onState && onState(true); };
      ws.onmessage = (m) => { try { onEvent(JSON.parse(m.data)); } catch (e) { console.error(e); } };
      ws.onclose = () => {
        onState && onState(false);
        setTimeout(open, delay);
        delay = Math.min(delay * 2, 10000);
      };
    }
    open();
  }

  /** Regroupe des appels rapprochés en un seul (ex. rafraîchir une liste à chaque événement). */
  function debounce(fn, ms = 300) {
    let t = null;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  }

  function esc(v) {
    return String(v === null || v === undefined ? '' : v)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  const money = (v) => (v === null || v === undefined || v === '') ? '—'
    : Number(v).toLocaleString('fr-FR').replace(/ | /g, ' ') + ' FCFA';

  const time = (v) => v ? new Date(v).toLocaleTimeString('fr-FR', { hour12: false }) : '—';
  const dateTime = (v) => v ? new Date(v).toLocaleString('fr-FR', { hour12: false }) : '—';

  /** Mise en forme lisible d'un message (JSON ou XML). */
  function pretty(payload, format) {
    if (!payload) return '';
    if (format === 'JSON') {
      try { return JSON.stringify(JSON.parse(payload), null, 2); } catch (e) { return payload; }
    }
    if (format === 'XML') {
      let depth = 0;
      return payload.replace(/>\s*</g, '>\n<').split('\n').map((line) => {
        if (/^<\//.test(line)) depth = Math.max(depth - 1, 0);
        const out = '  '.repeat(depth) + line;
        if (/^<[^!?/][^>]*[^/]>$/.test(line) && !/<\/[^>]+>$/.test(line)) depth++;
        return out;
      }).join('\n');
    }
    return payload;
  }

  /** Famille visuelle d'un statut : ok, error, warn, info. */
  const OK = ['INVOICED', 'STOCK_RESERVED', 'DELIVERED', 'WORKFLOW_COMPLETED', 'DELIVERY_ASSIGNED', 'REPLAYED'];
  const ERROR = ['FAILED', 'DEAD_LETTERED', 'REJECTED_OUT_OF_STOCK'];
  const WARN = ['RETRYING', 'DUPLICATE_IGNORED', 'DUPLICATE_SENT'];
  function tone(status) {
    if (ERROR.includes(status)) return 'error';
    if (WARN.includes(status)) return 'warn';
    if (OK.includes(status)) return 'ok';
    return 'info';
  }

  /** Libellés en français pour les clients et opérateurs. */
  const LABELS = {
    RECEIVED: 'Reçue', VALIDATING: 'Vérification du stock', STOCK_RESERVED: 'Stock réservé', INVOICED: 'Facturée',
    REJECTED_OUT_OF_STOCK: 'Refusée (rupture)', RETRYING: 'Nouvelle tentative', FAILED: 'En échec',
    DELIVERY_ASSIGNED: 'Livreur assigné', IN_DELIVERY: 'En livraison', DELIVERED: 'Livrée',
    ASSIGNED: 'À prendre en charge'
  };
  const label = (s) => LABELS[s] || s;

  window.Sidam = { api, connectLive, debounce, esc, money, time, dateTime, pretty, tone, label };
})();
