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

export default {
  async fetch(request, env) {
    try {
      if (!env.ACCESS_KEY || env.ACCESS_KEY.length < 12) {
        return text(500, 'Not set up: add an ACCESS_KEY secret of 12 or more characters.');
      }
      if (request.method !== 'GET' && request.method !== 'HEAD') return text(405, 'Method not allowed');

      const url = new URL(request.url);
      if (url.pathname !== '/ping' && url.pathname !== '/p') return text(404, 'Not found');

      if (!(await keyOk(request, env))) {
        await new Promise((r) => setTimeout(r, 600)); // slow down guessing
        return text(401, 'Wrong access key');
      }
      if (url.pathname === '/ping') return text(200, 'ok');

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
    } catch (err) {
      return text(500, 'Something went wrong');
    }
  },
};
