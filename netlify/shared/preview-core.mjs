// WatchLater+ web: finds the title and thumbnail of a saved video link.
// Same steps as the phone app (Previews.java / PageInfo.java), run on the site's helper
// because a web page is not allowed to read Facebook, TikTok or Instagram pages itself.

const UA_DESKTOP = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
const UA_PHONE = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const HTML = 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5';
const IMAGE = 'image/webp,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5';

/* ---------- which addresses the helper may open ---------- */

/** Only normal public https web addresses: no IP numbers, no local names, no odd ports. */
export function safeUrl(raw) {
  let u;
  try { u = new URL(String(raw)); } catch (e) { return null; }
  if (u.protocol !== 'https:' || u.username || u.password || u.port) return null;
  const h = u.hostname.toLowerCase();
  if (!h.includes('.') || h.includes(':') || /^[\d.]+$/.test(h) || h.endsWith('.')) return null;
  if (/(^|\.)(localhost|local|internal|lan|home|corp|test|invalid|example)$/.test(h)) return null;
  return u;
}

function host(url) {
  try { return new URL(url).hostname.toLowerCase().replace(/^www\./, ''); } catch (e) { return ''; }
}
const on = (h, site) => h === site || h.endsWith('.' + site);
const site = url => { const p = host(url).split('.'); return p.length <= 2 ? p.join('.') : p.slice(-2).join('.'); };
const isYouTube = url => { const h = host(url); return on(h, 'youtube.com') || h === 'youtu.be'; };
const enc = encodeURIComponent;

/* ---------- one web request ---------- */

async function readCapped(res, max) {
  if (!res.body) return Buffer.alloc(0);
  const reader = res.body.getReader();
  const parts = []; let size = 0, cut = false;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(Buffer.from(value)); size += value.length;
    if (size >= max) { cut = true; try { await reader.cancel(); } catch (e) { /* ignore */ } break; }
  }
  const b = Buffer.concat(parts);
  b.cut = cut;
  return b;
}

/**
 * One GET that follows redirects itself, keeping the cookies each step hands out.
 * body stays null unless the final answer is a normal 200. Never throws: code 0 = not reachable.
 */
export async function http(ctx, url, opt = {}) {
  const max = opt.max || 600000, accept = opt.accept || HTML, page = accept.startsWith('text/html');
  const r = { code: 0, finalUrl: url, hops: [], body: null, type: '' };
  let current = url;
  const cookies = new Map();
  for (let hop = 0; hop < 8; hop++) {
    if (/^http:\/\//i.test(current)) current = 'https://' + current.slice(7);
    r.finalUrl = current; r.hops.push(current);
    if (!safeUrl(current)) { r.code = -1; return r; }
    const left = ctx.deadline - Date.now();
    if (left < 400) { r.code = -3; return r; }
    const headers = { 'User-Agent': opt.desktop ? UA_DESKTOP : UA_PHONE, 'Accept': accept, 'Accept-Language': 'en-US,en;q=0.9' };
    if (opt.referer) headers['Referer'] = opt.referer;
    if (page) Object.assign(headers, { 'Sec-Fetch-Dest': 'document', 'Sec-Fetch-Mode': 'navigate', 'Sec-Fetch-Site': 'none', 'Sec-Fetch-User': '?1', 'Upgrade-Insecure-Requests': '1' });
    if (cookies.size) headers['Cookie'] = [...cookies].map(([k, v]) => k + '=' + v).join('; ');
    let res;
    try {
      res = await ctx.fetch(current, { redirect: 'manual', headers, signal: AbortSignal.timeout(Math.min(5000, left)) });
    } catch (e) { r.code = 0; r.err = e && e.name || 'error'; return r; }
    ctx.reached = true;
    r.code = res.status;
    const set = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const line of set) {
      const pair = line.split(';')[0], eq = pair.indexOf('=');
      if (eq > 0) cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      try { if (res.body) await res.body.cancel(); } catch (e) { /* ignore */ }
      if (!loc) return r;
      let next;
      try { next = new URL(loc, current).toString(); } catch (e) { return r; }
      if (site(next) !== site(current)) cookies.clear();   // never carry cookies to another site
      current = next;
      continue;
    }
    if (res.status !== 200) { try { if (res.body) await res.body.cancel(); } catch (e) { /* ignore */ } return r; }
    r.type = (res.headers.get('content-type') || '').toLowerCase();
    try { r.body = await readCapped(res, max); } catch (e) { r.body = null; r.err = 'cut off'; }
    return r;
  }
  r.code = -2;   // went round in circles
  return r;
}
const text = r => (r && r.body ? r.body.toString('utf8') : '');

/* ---------- reading a page (PageInfo.java) ---------- */

const info = () => ({ title: '', desc: '', image: '' });
const squash = s => String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
const first = (...c) => { for (const s of c) if (s && String(s).trim()) return String(s).trim(); return ''; };
const NAMED = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>', nbsp: ' ' };

export function decode(s) {
  if (!s || s.indexOf('&') < 0) return s || '';
  return s.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (all, e) => {
    try {
      if (/^#x/i.test(e)) return String.fromCodePoint(parseInt(e.slice(2), 16));
      if (e[0] === '#') return String.fromCodePoint(parseInt(e.slice(1), 10));
    } catch (x) { return all; }
    return Object.prototype.hasOwnProperty.call(NAMED, e) ? NAMED[e] : all;
  });
}

const STATS = /^[\d.,]+\s*[kmb]?\s+(views?|reactions?|likes?|comments?|shares?|plays?)\b.*$/;
const SITE_WORDS = new Set(['facebook', 'tiktok', 'instagram', 'youtube', 'reels', 'video', 'watch', 'facebook watch', 'tiktok - make your day']);

/** Makes a page title readable: drops view counts, the site name, and login-wall text. */
export function cleanTitle(raw) {
  let s = squash(raw);
  if (!s) return '';
  s = s.replace(/\s+[-|•·]\s+(YouTube|Facebook|TikTok|Instagram)\s*$/i, '');
  s = s.split(/\s+\|\s+/).filter(part => { const low = part.toLowerCase().trim(); return low && !STATS.test(low) && !SITE_WORDS.has(low); })
    .map(p => p.trim()).join(' · ');
  const low = s.toLowerCase();
  const wall = low.includes('log in') || low.includes('log into') || low.includes('sign up') || low.includes('login •')
    || low === 'login' || low === 'discover popular videos';
  const shortErr = low.length < 45 && ['page not found', 'not available', "isn't available", 'error', 'just a moment',
    'access denied', 'security check', 'redirecting'].some(w => low.includes(w));
  if (!low || wall || shortErr) return '';
  if (s.length > 160) s = s.slice(0, 157).trim() + '...';
  return s;
}

const attrs = tag => {
  const out = []; const re = /([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))/g; let a;
  while ((a = re.exec(tag))) out.push([a[1].toLowerCase(), a[3] != null ? a[3] : a[4] != null ? a[4] : a[5]]);
  return out;
};

export function parse(html) {
  const p = info();
  if (!html) return p;
  const meta = new Map();
  for (const m of html.matchAll(/<meta\b[^>]*>/gi)) {
    let key = null, content = null;
    for (const [n, v] of attrs(m[0])) {
      if (n === 'property' || n === 'name') key = v.toLowerCase();
      else if (n === 'content') content = v;
    }
    if (key != null && content != null && content.trim() && !meta.has(key)) meta.set(key, decode(content).trim());
  }
  const t = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
  p.title = cleanTitle(first(meta.get('og:title'), meta.get('twitter:title'), t ? decode(t[1]) : ''));
  p.desc = squash(first(meta.get('og:description'), meta.get('twitter:description'), meta.get('description'))).slice(0, 300);
  p.image = first(meta.get('og:image:secure_url'), meta.get('og:image'), meta.get('twitter:image'));
  if (!/^https?:\/\//.test(p.image)) p.image = '';
  if (!p.title) { p.image = ''; p.desc = ''; }   // a login wall or an error page
  return p;
}

const J_THUMB = /"(?:preferred_thumbnail|thumbnailImage|previewImage|thumbnail_image|display_resources)"\s*:\s*[\[{]+(?:\s*"image"\s*:\s*\{)?\s*"(?:uri|src)"\s*:\s*"(https:[^"]+)"/g;
const J_THUMB2 = /"(?:first_frame_thumbnail|thumbnail_url|thumbnailUrl|thumbnail_src|display_url)"\s*:\s*"(https:[^"]+)"/g;
const J_TEXT = /"(?:message|savable_description|title)"\s*:\s*\{\s*"text"\s*:\s*"((?:[^"\\]|\\.){3,800})"/;

/** True for a picture on Facebook's / Instagram's media servers (not their logo or icons). */
function isMedia(url) {
  if (!url || !url.startsWith('https://')) return false;
  const u = url.toLowerCase();
  if (!(u.includes('fbcdn.net') || u.includes('cdninstagram.com'))) return false;
  return !u.includes('//static.') && !u.includes('rsrc.php') && !u.includes('/emoji');
}

/** JSON text (as found inside a page's scripts) back to normal text. */
export function unjson(s) {
  if (!s || s.indexOf('\\') < 0) return s || '';
  try { return JSON.parse('"' + s + '"'); } catch (e) { /* fall through */ }
  return s.replace(/\\u([0-9a-fA-F]{4})/g, (a, h) => String.fromCharCode(parseInt(h, 16))).replace(/\\[nrt]/g, ' ').replace(/\\(.)/g, '$1');
}

function jsonThumb(html) {
  for (const re of [J_THUMB, J_THUMB2]) {
    for (const m of html.matchAll(re)) { const u = decode(unjson(m[1])); if (isMedia(u)) return u; }
  }
  return '';
}

function parseFacebook(html) {
  const p = info();
  if (!html) return p;
  const og = parse(html);
  p.title = og.title; p.desc = og.desc;
  if (isMedia(og.image)) p.image = og.image;
  if (!p.image) {
    for (const m of html.matchAll(/<meta\b[^>]*>/gi)) {
      if (!m[0].includes('og:image') && !m[0].includes('twitter:image')) continue;
      for (const [n, v] of attrs(m[0])) if (n === 'content' && isMedia(decode(v))) p.image = decode(v);
      if (p.image) break;
    }
  }
  if (!p.image) p.image = jsonThumb(html);
  if (!p.title) { const t = J_TEXT.exec(html); if (t) p.title = cleanTitle(unjson(t[1])); }
  return p;
}

function parseEmbed(html) {
  const p = info();
  if (!html) return p;
  for (const v of html.matchAll(/<video\b[^>]*\sposter\s*=\s*"([^"]+)"/gi)) { const u = decode(v[1]); if (isMedia(u)) { p.image = u; break; } }
  if (!p.image) p.image = jsonThumb(html);
  if (!p.image) {
    for (const im of html.matchAll(/<img\b[^>]*>/gi)) {
      let src = '', cls = '', w = -1;
      for (const [n, v] of attrs(im[0])) {
        if (n === 'src') src = decode(v);
        else if (n === 'class') cls = v.toLowerCase();
        else if ((n === 'width' || n === 'height') && /^\s*\d+\s*$/.test(v)) w = Math.max(w, parseInt(v, 10));
      }
      if (!isMedia(src) || cls.includes('profile') || cls.includes('avatar')) continue;
      if (w >= 0 && w < 100) continue;
      const sm = /[/_.]((?:p|s|c)?\d{2,3})x(\d{2,3})[/_.]/.exec(src);
      if (sm && parseInt(sm[2], 10) < 200) continue;
      p.image = src; break;
    }
  }
  if (!p.image) {
    for (const b of html.matchAll(/background-image\s*:\s*url\(\s*(?:&quot;|["'])?([^)"']+?)(?:&quot;|["'])?\s*\)/gi)) {
      const u = decode(b[1].trim()); if (isMedia(u)) { p.image = u; break; }
    }
  }
  const c = /class="[^"]*(?:Caption|userContent)[^"]*"[^>]*>([\s\S]*?)(?:<\/div>|<div\b)/i.exec(html);
  if (c) {
    p.title = cleanTitle(decode(c[1].replace(/<a\b[^>]*class="[^"]*(?:Username|CaptionUsername)[^"]*"[^>]*>[\s\S]*?<\/a>/gi, ' ').replace(/<[^>]+>/g, ' ')));
  }
  if (!p.title) { const t = J_TEXT.exec(html); if (t) p.title = cleanTitle(unjson(t[1])); }
  return p;
}

function facebookId(...urls) {
  for (const u of urls) {
    if (!u) continue;
    const d = u.replace(/%2F/gi, '/').replace(/%3F/gi, '?').replace(/%3D/gi, '=').replace(/%26/g, '&');
    const m = /(?:\/reel\/|\/reels\/|\/videos\/(?:[^/?#]+\/)?|[?&]v=|video_id=|story_fbid=)(\d{6,})/.exec(d);
    if (m) return m[1];
  }
  return '';
}

function instagramEmbed(url) {
  const m = /instagram\.com\/(?:[^/]+\/)?(reel|reels|p|tv)\/([A-Za-z0-9_-]{5,})/.exec(url || '');
  if (!m) return '';
  return 'https://www.instagram.com/' + (m[1].startsWith('reel') ? 'reel' : m[1]) + '/' + m[2] + '/embed/captioned/';
}

/* ---------- where to look, per site (Previews.java) ---------- */

const found = p => (p.title ? ' title' : ' no title') + (p.image ? ', picture' : ', no picture');
function fill(into, from) {
  if (!into.title) into.title = from.title;
  if (!into.desc) into.desc = from.desc;
  if (!into.image) into.image = from.image;
}
const isLoginWall = u => { u = (u || '').toLowerCase(); return u.includes('/login') || u.includes('login.php') || u.includes('/checkpoint') || u.includes('/accounts/login'); };

function oembed(json) {
  const p = info();
  if (!json) return p;
  try {
    const o = JSON.parse(json);
    p.title = cleanTitle(o.title || '');
    const author = String(o.author_name || '').trim();
    if (!p.title && author) p.title = 'Video by ' + author;
    p.desc = author;
    if (String(o.thumbnail_url || '').startsWith('https://')) p.image = o.thumbnail_url;
    // TikTok answers about the creator (not the video) for links it does not see as a video
    p.profile = o.embed_type === 'profile' || /'s Creator Profile$/i.test(String(o.title || ''));
  } catch (e) { /* not the answer we hoped for */ }
  return p;
}

/* TikTok */
const TT_ID = /\/(?:video|photo|v)\/(\d{8,25})|[?&](?:item_id|share_item_id|aweme_id)=(\d{8,25})/;
const TT_USER = /\/@([\w.\-]+)/;
const TT_PAGE = /tiktok\.com(?:\\?\/|\\u002F)@([\w.\-]+)(?:\\?\/|\\u002F)(?:video|photo)(?:\\?\/|\\u002F)(\d{8,25})/;
// where TikTok's pages keep the cover picture, best first
const TT_COVERS = [
  /"(?:originCover|coversOrigin)"\s*:\s*\[?\s*"(https?:[^"]+)"/,
  /"(?:cover|covers)"\s*:\s*\[?\s*"(https?:[^"]+)"/,
  /"(?:thumbnailUrl|thumbnail_url|poster)"\s*:\s*\[?\s*"(https?:[^"]+)"/,
  /"(?:imageURL|displayImage|imagePostCover|shareCover)"\s*:\s*\{[^{}]{0,400}?"urlList"\s*:\s*\[\s*"(https?:[^"]+)"/,
  /"shareCover"\s*:\s*\[\s*(?:""\s*,\s*)?"(https?:[^"]+)"/,
  /"(?:dynamicCover|coversDynamic)"\s*:\s*\[?\s*"(https?:[^"]+)"/,
];
const TT_DESC = [/"desc"\s*:\s*"((?:[^"\\]|\\.){3,400})"/, /"text"\s*:\s*"((?:[^"\\]|\\.){3,400})"/];
const TT_AUTHOR = /"(?:nickname|authorName)"\s*:\s*"((?:[^"\\]|\\.){1,80})"/;

export function tiktokId(url) { const m = TT_ID.exec(url || ''); return m ? (m[1] || m[2]) : ''; }
export function tiktokUser(url) { const m = TT_USER.exec(url || ''); return m ? m[1] : ''; }

/** Cover picture and caption out of a TikTok page (the video page or the player page). */
export function tiktokFromHtml(html, vid) {
  const p = info();
  if (!html) return p;
  // start reading at this video's own entry when the page lists more than one
  let at = vid ? html.indexOf('"id":"' + vid + '"') : -1;
  const parts = at > 0 ? [html.slice(at), html] : [html];
  for (const part of parts) {
    for (const re of TT_COVERS) {
      const m = re.exec(part);
      if (!m) continue;
      const u = unjson(m[1]);
      if (u.startsWith('https://')) { p.image = u; break; }
    }
    if (p.image) break;
  }
  for (const re of TT_DESC) {
    const d = re.exec(parts[0]);
    if (d) { p.title = cleanTitle(unjson(d[1])); if (p.title) break; }
  }
  const a = TT_AUTHOR.exec(parts[0]);
  if (a) p.desc = squash(unjson(a[1]));
  if (!p.title && p.desc && p.image) p.title = 'Video by ' + p.desc;
  const og = parse(html);
  if (og.title && !/^tiktok\b/i.test(og.title)) fill(p, og);
  else if (!p.image && og.image) p.image = og.image;
  return p;
}

/**
 * TikTok share links are short (vt.tiktok.com/...). Asked about the short link itself, TikTok's
 * info service can describe the creator instead of the video. So: follow the link, find the
 * video number, ask about the real video, then try the player page and the video page.
 */
async function tiktok(ctx, url) {
  const best = info(), log = ctx.log;
  let vid = tiktokId(url), user = tiktokUser(url), canon = vid ? url.split(/[?#]/)[0] : '', page = null;
  if (!vid) {
    page = await http(ctx, url, { max: 900000, desktop: true });
    log.push('TikTok link ' + page.code);
    for (const h of page.hops) {
      if (!vid) { vid = tiktokId(h); if (vid) canon = h.split(/[?#]/)[0]; }
      if (!user) user = tiktokUser(h);
    }
    if (!vid && page.body) {
      const m = TT_PAGE.exec(text(page));
      if (m) { vid = m[2]; if (!user) user = m[1]; }
    }
    log.push(vid ? ' (video ' + vid + ')' : ' (no video number)');
  }

  if (vid) {
    const real = /tiktok\.com\/@[\w.\-]+\/(?:video|photo)\/\d+$/.test(canon) ? canon
      : 'https://www.tiktok.com/@' + (user || 'tiktok') + '/video/' + vid;
    const r = await http(ctx, 'https://www.tiktok.com/oembed?url=' + enc(real), { max: 200000 });
    const x = oembed(text(r));
    if (x.profile) { x.title = ''; x.desc = ''; x.image = ''; }
    log.push('; info ' + r.code + found(x));
    fill(best, x);
    if (!best.image || !best.title) {
      const e = await http(ctx, 'https://www.tiktok.com/embed/v2/' + vid, { max: 900000, desktop: true });
      const y = tiktokFromHtml(text(e), vid);
      log.push('; player ' + e.code + found(y));
      fill(best, y);
    }
    if (!best.image || !best.title) {
      const samePage = page && page.body && tiktokId(page.finalUrl) === vid;
      const v = samePage ? page : await http(ctx, real, { max: 900000, desktop: true });
      const y = tiktokFromHtml(text(v), vid);
      log.push('; video page ' + v.code + found(y));
      fill(best, y);
    }
    if (best.title || best.image) return best;
  }

  // not a video (a creator's page, or something new): describe the link as it is
  const r = await http(ctx, 'https://www.tiktok.com/oembed?url=' + enc(url), { max: 200000 });
  const x = oembed(text(r));
  log.push('; link info ' + r.code + found(x));
  fill(best, x);
  if (!best.image && page && page.body) {
    const y = parse(text(page));
    fill(best, y);
    log.push('; from page' + found(y));
  }
  return best;
}

async function facebook(ctx, url) {
  const log = ctx.log, best = info();
  const www = url.replace(/^https?:\/\/(m|mbasic|web|touch|mobile)\.facebook\.com/i, 'https://www.facebook.com');
  const r = await http(ctx, www, { max: 900000, desktop: true });
  const wall = isLoginWall(r.finalUrl);
  log.push('Facebook page ' + r.code + (wall ? ' (asked to log in)' : ''));
  if (r.body && !wall) { const x = parseFacebook(text(r)); fill(best, x); log.push(found(x)); }

  if (!best.title || !best.image) {
    const vid = facebookId(url, r.finalUrl);
    const href = vid ? 'https://www.facebook.com/video.php?v=' + vid : (wall ? www : r.finalUrl);
    const e = await http(ctx, 'https://www.facebook.com/plugins/video.php?show_text=true&width=500&href=' + enc(href), { max: 700000, desktop: true });
    log.push('; embed ' + e.code + (vid ? '' : ' (no video number)'));
    if (e.body) { const x = parseEmbed(text(e)); fill(best, x); log.push(found(x)); }
  }
  if (!best.title && !best.image) {
    const m = await http(ctx, url, { max: 700000 });
    const wall2 = isLoginWall(m.finalUrl);
    log.push('; phone page ' + m.code + (wall2 ? ' (asked to log in)' : ''));
    if (m.body && !wall2) { const x = parseFacebook(text(m)); fill(best, x); log.push(found(x)); }
  }
  return best;
}

async function instagram(ctx, url) {
  const log = ctx.log, best = info();
  const r = await http(ctx, url, { max: 900000, desktop: true });
  const wall = isLoginWall(r.finalUrl);
  log.push('Instagram page ' + r.code + (wall ? ' (asked to log in)' : ''));
  if (r.body && !wall) { const x = parseFacebook(text(r)); fill(best, x); log.push(found(x)); }
  if (!best.title || !best.image) {
    const embed = instagramEmbed(wall ? url : r.finalUrl) || instagramEmbed(url);
    if (embed) {
      const e = await http(ctx, embed, { max: 700000 });
      log.push('; embed ' + e.code);
      if (e.body) { const x = parseEmbed(text(e)); fill(best, x); log.push(found(x)); }
    }
  }
  return best;
}

async function load(ctx, url) {
  const h = host(url);
  if (isYouTube(url)) {
    const r = await http(ctx, 'https://www.youtube.com/oembed?format=json&url=' + enc(url), { max: 200000 });
    ctx.log.push('YouTube info ' + r.code);
    const p = oembed(text(r));
    p.image = '';   // the page shows YouTube's own thumbnail directly
    return p;
  }
  if (on(h, 'tiktok.com')) return tiktok(ctx, url);
  if (on(h, 'facebook.com') || on(h, 'fb.watch') || on(h, 'fb.com') || on(h, 'fb.gg')) return facebook(ctx, url);
  if (on(h, 'instagram.com')) return instagram(ctx, url);
  const r = await http(ctx, url, { max: 600000 });
  const p = r.body ? parse(text(r)) : info();
  ctx.log.push('page ' + r.code + found(p));
  return p;
}

/* ---------- the picture ---------- */

function imageType(b, declared) {
  if (b.length > 12) {
    if (b[0] === 0xFF && b[1] === 0xD8) return 'image/jpeg';
    if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4E && b[3] === 0x47) return 'image/png';
    if (b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
    if (b.toString('ascii', 0, 3) === 'GIF') return 'image/gif';
    if (b.toString('ascii', 4, 8) === 'ftyp') return /avif|avis/.test(b.toString('ascii', 8, 12)) ? 'image/avif' : 'image/heic';
  }
  return /^image\/[a-z0-9.+-]+/.test(declared) ? declared.split(';')[0] : '';
}

async function picture(ctx, imageUrl) {
  const ref = host(imageUrl).includes('tiktok') ? 'https://www.tiktok.com/' : null;
  for (const desktop of [false, true]) {
    const r = await http(ctx, imageUrl, { max: 1500000, accept: IMAGE, desktop, referer: ref });
    if (!r.body || r.body.cut || r.body.length < 100) continue;
    const type = imageType(r.body, r.type);
    if (!type) continue;
    return 'data:' + type + ';base64,' + r.body.toString('base64');
  }
  return '';
}

/** The whole job for one link. Returns {ok, t, d, i, g}: title, description, picture (data URL), what happened. */
export async function preview(rawUrl, env = {}) {
  const ctx = { fetch: env.fetch || fetch, deadline: env.deadline || Date.now() + 20000, log: [], reached: false };
  const u = safeUrl(String(rawUrl || '').replace(/^http:\/\//i, 'https://'));
  if (!u) return { ok: true, t: '', d: '', i: '', g: 'Not a normal web link' };
  let p = info();
  try { p = await load(ctx, u.toString()); } catch (e) { ctx.log.push('; problem: ' + (e && e.name || 'error')); }
  let i = '';
  if (p.image && !isYouTube(u.toString())) {
    try { i = await picture(ctx, p.image); } catch (e) { i = ''; }
    ctx.log.push(i ? '; picture saved' : '; picture could not be downloaded');
  }
  const g = ctx.log.join('').slice(0, 400);
  if (!ctx.reached) return { ok: false, t: '', d: '', i: '', g: 'The helper could not reach the site' };
  return { ok: true, t: p.title || '', d: p.desc || '', i, g };
}
