// Kosher Photos proxy Worker (Cloudflare Workers, free plan is enough).
//
// The Android app asks this Worker for an image, the Worker fetches it from
// Google and passes it back, so the phone only talks to YOUR Worker address and
// a DNS filter (NextDNS) can keep googleusercontent.com blocked.
//
// Rules this Worker enforces:
//   * Every request needs your access key in the "X-Access-Key" header.
//   * Only https addresses on the Google photo hosts below can be fetched.
//   * No cookies or login information are ever sent to Google or stored.
//   * Only images / video / zip files are passed back.
//
// Setup: Workers & Pages > Create > "Start with Hello World!" > Edit code >
// paste this whole file > Deploy. Then Settings > Variables and Secrets > add a
// Secret named ACCESS_KEY (12+ characters) and deploy again.

// Hosts the app is allowed to ask for (each one and all of its subdomains).
// Keep this list in step with PROXIED_HOSTS in ProxyFetcher.java.
const UPSTREAM_ALLOWED = ['googleusercontent.com', 'usercontent.google.com', 'ggpht.com'];

const UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36';

const MAX_REDIRECTS = 3;

// Headers copied from Google's answer back to the app (nothing else is passed on).
const PASS_HEADERS = [
  'Content-Type',
  'Content-Length',
  'Content-Range',
  'Accept-Ranges',
  'Content-Disposition',
  'ETag',
  'Last-Modified',
];

function hostAllowed(url) {
  if (url.protocol !== 'https:') return false;
  if (url.username || url.password) return false;
  if (url.port && url.port !== '443') return false;
  const h = url.hostname.toLowerCase();
  return UPSTREAM_ALLOWED.some((d) => h === d || h.endsWith('.' + d));
}

function typeAllowed(type) {
  const t = (type || '').toLowerCase();
  return (
    t.startsWith('image/') ||
    t.startsWith('video/') ||
    t.startsWith('application/zip') ||
    t.startsWith('application/octet-stream')
  );
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function text(status, body) {
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

async function keyOk(request, env) {
  const given = request.headers.get('X-Access-Key') || '';
  const a = await sha256Hex('k:' + given);
  const b = await sha256Hex('k:' + env.ACCESS_KEY);
  return safeEqual(a, b);
}

// ---------- shared-album helpers ----------
// Hosts we are willing to fetch an album page from (and follow redirects to).
const ALBUM_HOSTS = new Set(['photos.app.goo.gl', 'photos.google.com']);

// Hosts we are willing to fetch photo bytes from.
const IMAGE_HOST = /^lh[3-6]\.googleusercontent\.com$/;
const IMAGE_PATH = /^\/pw\/[A-Za-z0-9_-]{20,}$/;

function isAllowedAlbumUrl(url) {
  if (url.protocol !== 'https:') return false;
  if (!ALBUM_HOSTS.has(url.hostname)) return false;
  if (url.hostname === 'photos.google.com' && !url.pathname.includes('/share/')) return false;
  return true;
}

function isAllowedImageUrl(url) {
  return (
    url.protocol === 'https:' &&
    IMAGE_HOST.test(url.hostname) &&
    IMAGE_PATH.test(url.pathname) &&
    url.search === '' &&
    url.hash === ''
  );
}

function decodeEntities(s) {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

// Reads a shared-album page and returns { title, items }.
// Each item is a stable base URL for one photo; the proxy appends a size suffix later.
function parseAlbum(html) {
  let title = '';
  const t =
    html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']/i) ||
    html.match(/<meta[^>]+content=["']([^"']*)["'][^>]+property=["']og:title["']/i);
  if (t) title = decodeEntities(t[1]).trim();

  const re = /https:\/\/lh[3-6]\.googleusercontent\.com\/pw\/[A-Za-z0-9_-]{20,}/g;
  const seen = new Set();
  const items = [];
  for (const m of html.matchAll(re)) {
    const base = m[0];
    if (seen.has(base)) continue;
    seen.add(base);
    items.push(base);
  }
  return { title, items };
}

function readCookie(header, name) {
  if (!header) return '';
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i === -1) continue;
    if (part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return '';
}


// NOTE: String.raw is used so backslashes in the client code stay as written.
// Do not use backticks or dollar-brace sequences inside the page code below.

const PAGE_HTML = String.raw`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex">
<meta name="theme-color" content="#101317">
<title>Photos</title>
<style>
  :root { --bg:#101317; --card:#1a1f26; --line:#2a313b; --text:#e9edf2; --muted:#93a0b0; --accent:#6aa7ff; --danger:#ff7b7b; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin:0; background:var(--bg); color:var(--text); font:16px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  .hidden { display:none !important; }
  .view { max-width:720px; margin:0 auto; padding:16px 16px 48px; padding-top:max(16px, env(safe-area-inset-top)); }
  header { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
  h1, h2 { margin:0; font-size:22px; }
  h2 { font-size:18px; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  input[type=url], input[type=password] { width:100%; padding:12px 14px; border-radius:12px; border:1px solid var(--line); background:var(--card); color:var(--text); font-size:16px; }
  button { padding:11px 16px; border-radius:12px; border:0; background:var(--accent); color:#06121f; font-weight:600; font-size:15px; cursor:pointer; }
  button.ghost { background:transparent; color:var(--accent); border:1px solid var(--line); }
  button.danger { background:transparent; color:var(--danger); border:1px solid var(--line); padding:8px 12px; }
  form.row { display:flex; gap:8px; margin:12px 0 16px; }
  ul#albumList { list-style:none; padding:0; margin:0; display:grid; gap:8px; }
  ul#albumList li { display:flex; gap:8px; align-items:center; }
  ul#albumList li button.open { flex:1; text-align:left; background:var(--card); color:var(--text); border:1px solid var(--line); font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .hint { color:var(--muted); font-size:14px; }
  .err { color:var(--danger); min-height:1.4em; }
  .bar { display:flex; justify-content:space-between; align-items:center; gap:8px; margin:4px 0 12px; color:var(--muted); font-size:14px; }
  .bar label { display:flex; align-items:center; gap:8px; }
  #grid { display:grid; grid-template-columns:repeat(3, 1fr); gap:4px; }
  @media (min-width:560px) { #grid { grid-template-columns:repeat(4, 1fr); } }
  .cell { position:relative; aspect-ratio:1; background:var(--card); overflow:hidden; border-radius:6px; }
  .cell img { width:100%; height:100%; object-fit:cover; display:block; }
  #grid.blur .cell:not(.shown) img { filter:blur(22px) brightness(.8); transform:scale(1.15); }
  #toast { position:fixed; left:50%; bottom:calc(24px + env(safe-area-inset-bottom)); transform:translateX(-50%); background:#2b3340; color:var(--text); padding:10px 16px; border-radius:12px; max-width:90vw; z-index:50; font-size:14px; box-shadow:0 6px 24px rgba(0,0,0,.4); }
  #viewer { position:fixed; inset:0; background:#000; z-index:30; display:flex; flex-direction:column; }
  #viewer .top, #viewer .bottom { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:10px 12px; padding-top:max(10px, env(safe-area-inset-top)); color:#fff; }
  #viewer .bottom { padding-bottom:max(10px, env(safe-area-inset-bottom)); }
  #viewer .stage { flex:1; display:flex; align-items:center; justify-content:center; min-height:0; }
  #viewer img { max-width:100%; max-height:100%; object-fit:contain; }
  #viewer button { background:rgba(255,255,255,.14); color:#fff; }
  #viewer button.danger { color:#ff9a9a; border:0; background:rgba(255,255,255,.14); }
</style>
</head>
<body>

<div id="toast" class="hidden"></div>

<section id="login" class="view hidden">
  <header><h1>Photos</h1></header>
  <form id="loginForm" class="row">
    <input id="key" type="password" placeholder="Access key" autocomplete="current-password" required>
    <button type="submit">Unlock</button>
  </form>
  <p id="loginErr" class="err"></p>
</section>

<section id="home" class="view hidden">
  <header>
    <h1>Albums</h1>
    <button id="logout" class="ghost" type="button">Lock</button>
  </header>
  <form id="addForm" class="row">
    <input id="link" type="url" placeholder="Paste a shared album link" required>
    <button type="submit">Add</button>
  </form>
  <ul id="albumList"></ul>
  <p id="emptyHint" class="hint hidden">No albums yet. Ask a friend for the "Share link" of their Google Photos album, then paste it above.</p>
</section>

<section id="album" class="view hidden">
  <header>
    <button id="back" class="ghost" type="button">Back</button>
    <h2 id="albumTitle"></h2>
  </header>
  <div class="bar">
    <label><input type="checkbox" id="blurToggle"> Blur thumbnails until tapped</label>
    <button id="unhide" class="ghost hidden" type="button"></button>
  </div>
  <p id="albumMsg" class="hint"></p>
  <div id="grid"></div>
</section>

<div id="viewer" class="hidden">
  <div class="top">
    <button id="vClose" type="button">Close</button>
    <span id="vCount"></span>
    <button id="vHide" class="danger" type="button">Hide photo</button>
  </div>
  <div class="stage"><img id="vImg" alt=""></div>
  <div class="bottom">
    <button id="vPrev" type="button">Previous</button>
    <button id="vNext" type="button">Next</button>
  </div>
</div>

<script>
(function () {
  var $ = function (s) { return document.querySelector(s); };

  var store = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  };

  var albums = store.get('pp_albums', []);
  var hidden = store.get('pp_hidden', {});
  var blurOn = store.get('pp_blur', false);
  var currentAlbum = null;
  var visible = [];
  var idx = 0;
  var toastTimer = null;

  function toast(msg) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.add('hidden'); }, 4000);
  }

  function show(name) {
    ['login', 'home', 'album'].forEach(function (v) {
      $('#' + v).classList.toggle('hidden', v !== name);
    });
  }

  function api(path, opts) {
    var o = opts || {};
    o.credentials = 'same-origin';
    return fetch(path, o).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (r.status === 401 && path !== '/login') { show('login'); throw new Error('Please unlock first'); }
        if (!r.ok) { throw new Error(j.error || ('Error ' + r.status)); }
        return j;
      });
    });
  }

  // ---------- login ----------
  $('#loginForm').addEventListener('submit', function (e) {
    e.preventDefault();
    $('#loginErr').textContent = '';
    api('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: $('#key').value })
    }).then(function () {
      $('#key').value = '';
      renderHome();
    }).catch(function (err) { $('#loginErr').textContent = err.message; });
  });

  $('#logout').addEventListener('click', function () {
    api('/logout', { method: 'POST' }).then(function () { show('login'); });
  });

  // ---------- home ----------
  function saveAlbums() { store.set('pp_albums', albums); }

  function renderHome() {
    show('home');
    var ul = $('#albumList');
    ul.innerHTML = '';
    albums.forEach(function (a, i) {
      var li = document.createElement('li');
      var open = document.createElement('button');
      open.className = 'open';
      open.type = 'button';
      open.textContent = a.title || ('Album ' + (i + 1));
      open.addEventListener('click', function () { openAlbum(a); });
      var del = document.createElement('button');
      del.className = 'danger';
      del.type = 'button';
      del.textContent = 'Remove';
      del.addEventListener('click', function () {
        if (confirm('Remove this album from the app?')) {
          albums.splice(i, 1);
          saveAlbums();
          renderHome();
        }
      });
      li.appendChild(open);
      li.appendChild(del);
      ul.appendChild(li);
    });
    $('#emptyHint').classList.toggle('hidden', albums.length > 0);
  }

  $('#addForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var link = $('#link').value.trim();
    if (albums.some(function (a) { return a.link === link; })) { toast('That album is already added'); return; }
    toast('Checking the link...');
    api('/api/album?link=' + encodeURIComponent(link)).then(function (j) {
      albums.push({ link: link, title: j.title || '' });
      saveAlbums();
      $('#link').value = '';
      renderHome();
      toast('Added (' + j.count + ' photos found)');
    }).catch(function (err) { toast(err.message); });
  });

  // ---------- album ----------
  function hiddenCount(items) {
    return items.filter(function (it) { return hidden[it.id]; }).length;
  }

  function openAlbum(a) {
    currentAlbum = { meta: a, items: [] };
    show('album');
    $('#albumTitle').textContent = a.title || 'Album';
    $('#albumMsg').textContent = 'Loading...';
    $('#grid').innerHTML = '';
    api('/api/album?link=' + encodeURIComponent(a.link)).then(function (j) {
      currentAlbum.items = j.items;
      if (j.title && j.title !== a.title) { a.title = j.title; saveAlbums(); $('#albumTitle').textContent = j.title; }
      renderGrid();
    }).catch(function (err) { $('#albumMsg').textContent = err.message; });
  }

  function renderGrid() {
    var items = currentAlbum.items;
    visible = items.filter(function (it) { return !hidden[it.id]; });
    var grid = $('#grid');
    grid.innerHTML = '';
    grid.classList.toggle('blur', blurOn);
    $('#blurToggle').checked = blurOn;

    var h = hiddenCount(items);
    var ub = $('#unhide');
    ub.classList.toggle('hidden', h === 0);
    ub.textContent = 'Restore ' + h + ' hidden';

    $('#albumMsg').textContent = visible.length + ' photos' + (h ? ' (' + h + ' hidden)' : '');

    visible.forEach(function (it, i) {
      var cell = document.createElement('div');
      cell.className = 'cell';
      var img = document.createElement('img');
      img.loading = 'lazy';
      img.decoding = 'async';
      img.alt = '';
      img.src = it.thumb;
      cell.appendChild(img);
      cell.addEventListener('click', function () {
        if (blurOn && !cell.classList.contains('shown')) { cell.classList.add('shown'); return; }
        openViewer(i);
      });
      grid.appendChild(cell);
    });
  }

  $('#blurToggle').addEventListener('change', function (e) {
    blurOn = e.target.checked;
    store.set('pp_blur', blurOn);
    renderGrid();
  });

  $('#unhide').addEventListener('click', function () {
    if (!confirm('Show all hidden photos in this album again?')) return;
    currentAlbum.items.forEach(function (it) { delete hidden[it.id]; });
    store.set('pp_hidden', hidden);
    renderGrid();
  });

  $('#back').addEventListener('click', function () { renderHome(); });

  // ---------- viewer ----------
  function openViewer(i) {
    idx = i;
    $('#viewer').classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    drawViewer();
  }

  function closeViewer() {
    $('#viewer').classList.add('hidden');
    $('#vImg').removeAttribute('src');
    document.body.style.overflow = '';
  }

  function drawViewer() {
    if (!visible.length) { closeViewer(); renderGrid(); return; }
    if (idx < 0) idx = visible.length - 1;
    if (idx >= visible.length) idx = 0;
    $('#vImg').src = visible[idx].full;
    $('#vCount').textContent = (idx + 1) + ' / ' + visible.length;
  }

  $('#vClose').addEventListener('click', closeViewer);
  $('#vPrev').addEventListener('click', function () { idx--; drawViewer(); });
  $('#vNext').addEventListener('click', function () { idx++; drawViewer(); });
  $('#vHide').addEventListener('click', function () {
    var it = visible[idx];
    if (!it) return;
    hidden[it.id] = true;
    store.set('pp_hidden', hidden);
    visible.splice(idx, 1);
    toast('Hidden. It will not load again.');
    if (!visible.length) { closeViewer(); renderGrid(); return; }
    drawViewer();
  });

  document.addEventListener('keydown', function (e) {
    if ($('#viewer').classList.contains('hidden')) return;
    if (e.key === 'Escape') { closeViewer(); renderGrid(); }
    if (e.key === 'ArrowLeft') { idx--; drawViewer(); }
    if (e.key === 'ArrowRight') { idx++; drawViewer(); }
  });

  var touchX = null;
  $('#viewer').addEventListener('touchstart', function (e) { touchX = e.touches[0].clientX; }, { passive: true });
  $('#viewer').addEventListener('touchend', function (e) {
    if (touchX === null) return;
    var dx = e.changedTouches[0].clientX - touchX;
    touchX = null;
    if (Math.abs(dx) < 60) return;
    idx += dx < 0 ? 1 : -1;
    drawViewer();
  }, { passive: true });

  // closing the viewer after hiding should refresh the grid
  $('#vClose').addEventListener('click', function () { renderGrid(); });

  // ---------- start ----------
  api('/api/me').then(function () { renderHome(); }).catch(function () { show('login'); });
})();
</script>
</body>
</html>`;

const COOKIE = 'pp_session';
const MAX_ALBUM_REDIRECTS = 5;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function withSecurityHeaders(res) {
  const h = new Headers(res.headers);
  h.set('X-Content-Type-Options', 'nosniff');
  h.set('Referrer-Policy', 'no-referrer');
  h.set('X-Frame-Options', 'DENY');
  h.set('X-Robots-Tag', 'noindex, nofollow');
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}

function json(status, obj, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...extra },
  });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function expectedToken(env) {
  return sha256Hex('pp1:' + env.ACCESS_KEY);
}

async function isAuthed(request, env) {
  const got = readCookie(request.headers.get('Cookie'), COOKIE);
  if (!got) return false;
  return safeEqual(got, await expectedToken(env));
}

async function handleLogin(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json(400, { error: 'Bad request' });
  }
  const supplied = typeof body.key === 'string' ? body.key : '';
  const a = await sha256Hex('k:' + supplied);
  const b = await sha256Hex('k:' + env.ACCESS_KEY);
  if (!safeEqual(a, b)) {
    await sleep(600); // slow down guessing
    return json(401, { error: 'Wrong key' });
  }
  const cookie =
    COOKIE + '=' + (await expectedToken(env)) + '; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict';
  return json(200, { ok: true }, { 'Set-Cookie': cookie });
}

function handleLogout() {
  const cookie = COOKIE + '=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict';
  return json(200, { ok: true }, { 'Set-Cookie': cookie });
}

async function fetchAlbumHtml(link) {
  let url;
  try {
    url = new URL(link);
  } catch {
    throw new HttpError(400, 'That is not a valid link');
  }
  for (let i = 0; i <= MAX_ALBUM_REDIRECTS; i++) {
    if (!isAllowedAlbumUrl(url)) {
      throw new HttpError(400, 'Only Google Photos shared-album links are allowed');
    }
    const res = await fetch(url.toString(), {
      redirect: 'manual',
      headers: { 'User-Agent': UA, 'Accept-Language': 'en-US,en;q=0.9', Accept: 'text/html' },
    });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('Location');
      if (!loc) throw new HttpError(502, 'Bad redirect from Google');
      url = new URL(loc, url);
      continue;
    }
    if (!res.ok) throw new HttpError(502, 'Google answered with status ' + res.status);
    return res.text();
  }
  throw new HttpError(502, 'Too many redirects');
}

async function handleAlbum(url) {
  const link = url.searchParams.get('link') || '';
  const html = await fetchAlbumHtml(link);
  const { title, items } = parseAlbum(html);
  if (items.length === 0) {
    throw new HttpError(
      404,
      'No photos found. The link may be private, expired, or Google changed the page format.'
    );
  }
  return json(200, {
    title,
    count: items.length,
    items: items.map((base) => ({
      id: base,
      thumb: '/img?m=t&u=' + encodeURIComponent(base),
      full: '/img?m=f&u=' + encodeURIComponent(base),
    })),
  });
}

async function handleImg(url) {
  let up;
  try {
    up = new URL(url.searchParams.get('u') || '');
  } catch {
    throw new HttpError(400, 'Bad image address');
  }
  if (!isAllowedImageUrl(up)) throw new HttpError(400, 'Image address not allowed');

  const suffix = url.searchParams.get('m') === 'f' ? '=w2048' : '=w480-h480-c';
  const res = await fetch(up.origin + up.pathname + suffix, {
    headers: { 'User-Agent': UA },
    cf: { cacheEverything: true, cacheTtl: 86400 },
  });
  const type = res.headers.get('Content-Type') || '';
  if (!res.ok || !type.startsWith('image/')) throw new HttpError(502, 'Could not load image');

  return new Response(res.body, {
    status: 200,
    headers: {
      'Content-Type': type,
      'Cache-Control': 'private, max-age=86400',
    },
  });
}

async function proxyPicture(request, url) {
  // ---- /p?u=<google address> ----
  let target;
  try {
    target = new URL(url.searchParams.get('u') || '');
  } catch {
    return text(400, 'Bad address');
  }

  const upstreamHeaders = { 'User-Agent': UA, Accept: request.headers.get('Accept') || '*/*' };
  const range = request.headers.get('Range');
  if (range && /^bytes=\d*-\d*$/.test(range)) upstreamHeaders.Range = range;

  let res;
  for (let i = 0; i <= MAX_REDIRECTS; i++) {
    if (!hostAllowed(target)) return text(400, 'Address not allowed');
    res = await fetch(target.toString(), {
      method: request.method,
      headers: upstreamHeaders,
      redirect: 'manual',
    });
    if (res.status >= 300 && res.status < 400 && res.headers.get('Location')) {
      target = new URL(res.headers.get('Location'), target);
      res = null;
      continue;
    }
    break;
  }
  if (!res) return text(502, 'Too many redirects');

  if (res.status >= 400) return text(res.status === 404 ? 404 : 502, 'Google answered ' + res.status);
  if (!typeAllowed(res.headers.get('Content-Type'))) return text(502, 'File type not allowed');

  const out = new Headers();
  for (const name of PASS_HEADERS) {
    const v = res.headers.get(name);
    if (v) out.set(name, v);
  }
  // The phone keeps its own copy, so repeat views do not hit this Worker again.
  out.set('Cache-Control', 'private, max-age=604800');
  out.set('X-Content-Type-Options', 'nosniff');
  return new Response(request.method === 'HEAD' ? null : res.body, { status: res.status, headers: out });
}

export default {
  async fetch(request, env) {
    try {
      if (!env.ACCESS_KEY || env.ACCESS_KEY.length < 12) {
        return text(500, 'Not set up: add an ACCESS_KEY secret of 12 or more characters.');
      }
      const url = new URL(request.url);
      const path = url.pathname;
      const isRead = request.method === 'GET' || request.method === 'HEAD';

      // ---- picture proxy for the app: /ping and /p?u=<google address> ----
      if (path === '/ping' || path === '/p') {
        if (!isRead) return text(405, 'Method not allowed');
        if (!(await keyOk(request, env))) {
          await sleep(600); // slow down guessing
          return text(401, 'Wrong access key');
        }
        if (path === '/ping') return text(200, 'ok');
        return await proxyPicture(request, url);
      }

      // ---- shared-album viewer (web page + its API) ----
      let res;
      if (isRead && path === '/') {
        res = new Response(PAGE_HTML, {
          headers: {
            'Content-Type': 'text/html; charset=utf-8',
            'Cache-Control': 'no-store',
            'Content-Security-Policy':
              "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
          },
        });
        // The app opens this page with the access key in a header: sign in right
        // away so the key never has to be typed or put in an address.
        if (request.headers.get('X-Access-Key') && (await keyOk(request, env))) {
          res.headers.append(
            'Set-Cookie',
            COOKIE + '=' + (await expectedToken(env)) + '; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict'
          );
        }
      } else if (request.method === 'POST' && path === '/login') {
        res = await handleLogin(request, env);
      } else if (request.method === 'POST' && path === '/logout') {
        res = handleLogout();
      } else if (path === '/api/me' || path === '/api/album' || path === '/img') {
        if (request.method !== 'GET') throw new HttpError(405, 'Method not allowed');
        if (!(await isAuthed(request, env))) throw new HttpError(401, 'Not signed in');
        if (path === '/api/me') res = json(200, { ok: true });
        else if (path === '/api/album') res = await handleAlbum(url);
        else res = await handleImg(url);
      } else {
        throw new HttpError(404, 'Not found');
      }
      return withSecurityHeaders(res);
    } catch (err) {
      if (err instanceof HttpError) return withSecurityHeaders(json(err.status, { error: err.message }));
      return text(500, 'Something went wrong');
    }
  },
};
