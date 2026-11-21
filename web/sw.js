/* WatchLater+ web: keeps the app on the phone so it opens with no internet. */
const VERSION = 'wl-f88ba59625';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png',
  './icon-maskable-512.png', './apple-touch-icon.png'];
const PICS = 'wl-pics';          // YouTube thumbnails seen before, so they show offline too
const MAX_PICS = 300;
const THUMBS = 'wl-thumbs';      // thumbnails the app fetched and shrank itself (Facebook, TikTok...)

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION && k !== PICS && k !== THUMBS).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function timeout(ms) { return new Promise((_, no) => setTimeout(() => no(new Error('slow')), ms)); }

async function page(req) {
  // newest app when online (quickly), the saved copy when offline
  try {
    const res = await Promise.race([fetch(req), timeout(3000)]);
    if (res && res.ok) {
      const c = await caches.open(VERSION);
      c.put('./index.html', res.clone());
      return res;
    }
  } catch (e) { /* offline or slow */ }
  return (await caches.match('./index.html')) || (await caches.match('./')) || Response.error();
}

async function picture(req) {
  const c = await caches.open(PICS);
  const hit = await c.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res && (res.ok || res.type === 'opaque')) {
    c.put(req, res.clone());
    c.keys().then(k => { if (k.length > MAX_PICS) k.slice(0, k.length - MAX_PICS).forEach(r => c.delete(r)); });
  }
  return res;
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (req.mode === 'navigate' && url.origin === location.origin) { e.respondWith(page(req)); return; }
  if (url.hostname === 'i.ytimg.com') { e.respondWith(picture(req)); return; }
  if (url.origin !== location.origin) return;
  if (url.pathname.includes('/.netlify/')) return;            // the site's helpers always go to the network
  if (url.pathname.includes('/thumbs/')) {
    e.respondWith(caches.open(THUMBS).then(c => c.match(req, {ignoreSearch: true}))
      .then(hit => hit || new Response('', {status: 404})));
    return;
  }
  e.respondWith(caches.open(VERSION).then(c => c.match(req, {ignoreSearch: true})).then(hit => hit || fetch(req)));
});
