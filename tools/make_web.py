#!/usr/bin/env python3
"""Makes the web version (folder web/) from the phone app's page, so both stay the same app.

Run from the repo root after changing app/src/main/assets/index.html:
    python3 tools/make_web.py
"""
import hashlib, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / 'app/src/main/assets/index.html'
WEB = ROOT / 'web'

page = SRC.read_text(encoding='utf-8')

HEAD = '''<meta name="description" content="Save videos from Facebook, TikTok, YouTube and Instagram to watch later, sorted into folders.">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="icon-192.png">
<link rel="apple-touch-icon" href="apple-touch-icon.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="WatchLater+">
<meta name="apple-mobile-web-app-status-bar-style" content="default">
'''
TAIL = '''<script>
/* Web version: keep a copy of the app on the phone so it opens with no internet. */
if ('serviceWorker' in navigator && window.isSecureContext) {
  window.addEventListener('load', () => { navigator.serviceWorker.register('sw.js').catch(() => {}); });
}
/* Ask the browser not to clear the saved list when the phone is low on space. */
try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (e) {}
</script>
'''
anchor = '<meta name="theme-color" content="#101014">\n'
assert anchor in page
out = page.replace(anchor, anchor + HEAD, 1)
assert out.count('</body>') == 1
out = out.replace('</body>', TAIL + '</body>', 1)
(WEB / 'index.html').write_text(out, encoding='utf-8')

# new cache name whenever the files change, so phones pick up the update
h = hashlib.sha1()
for name in ['index.html', 'manifest.webmanifest']:
    h.update((WEB / name).read_bytes())
sw = (WEB / 'sw.js').read_text(encoding='utf-8')
sw = re.sub(r"const VERSION = '[^']*';", "const VERSION = 'wl-" + h.hexdigest()[:10] + "';", sw, count=1)
(WEB / 'sw.js').write_text(sw, encoding='utf-8')
print('web/index.html and web/sw.js updated')
