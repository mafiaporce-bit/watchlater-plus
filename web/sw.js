/* WatchLater+ web: keeps the app on the phone so it opens with no internet. */
const VERSION = 'wl-56ba6ba8bc';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png',
  './icon-maskable-512.png', './apple-touch-icon.png'];
const PICS = 'wl-pics';          // YouTube thumbnails seen before, so they show offline too
const MAX_PICS = 300;
const THUMBS = 'wl-thumbs';
const ICONS = 'wl-icons';        // the little site icons (Threads, Reddit...)      // thumbnails the app fetched and shrank itself (Facebook, TikTok...)

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION && k !== PICS && k !== THUMBS && k !== ICONS).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

async function refresh(req) {
  try {
    const res = await fetch(req, {cache: 'no-store'});
    if (res && res.ok) { const c = await caches.open(VERSION); await c.put('./index.html', res.clone()); }
    return res;
  } catch (e) { return null; }
}

async function page(e) {
  // open straight away from the copy on the phone; fetch the newest one quietly for next time
  const saved = (await caches.match('./index.html')) || (await caches.match('./'));
  if (saved) { e.waitUntil(refresh(e.request)); return saved; }
  return (await refresh(e.request)) || Response.error();
}

async function icon(req) {
  const c = await caches.open(ICONS);
  const hit = await c.match(req);
  if (hit) return hit;
  try {
    const res = await fetch(req);
    if (res && res.ok) c.put(req, res.clone());
    return res;
  } catch (e) { return new Response('', {status: 504}); }
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
  if (req.mode === 'navigate' && url.origin === location.origin) { e.respondWith(page(e)); return; }
  if (url.hostname === 'i.ytimg.com') { e.respondWith(picture(req)); return; }
  if (url.origin !== location.origin) return;
  if (url.pathname.endsWith('/.netlify/functions/icon')) { e.respondWith(icon(req)); return; }
  if (url.pathname.includes('/.netlify/')) return;            // the site's helpers always go to the network
  if (url.pathname.includes('/thumbs/')) {
    e.respondWith(caches.open(THUMBS).then(c => c.match(req, {ignoreSearch: true}))
      .then(hit => hit || new Response('', {status: 404})));
    return;
  }
  e.respondWith(caches.open(VERSION).then(c => c.match(req, {ignoreSearch: true})).then(hit => hit || fetch(req)));
});
