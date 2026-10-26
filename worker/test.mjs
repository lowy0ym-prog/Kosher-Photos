// Run with:  node worker/test.mjs      (Google is mocked; nothing leaves this computer)
import worker from './worker.js';

let passed = 0;
let failed = 0;
function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log('  ok   ' + name);
  } else {
    failed++;
    console.log('  FAIL ' + name + (extra ? '  -> ' + extra : ''));
  }
}

const KEY = 'correct-horse-battery-staple';
const env = { ACCESS_KEY: KEY };
const realFetch = globalThis.fetch;
const seen = [];
let handler = async () => new Response('x', { status: 200, headers: { 'Content-Type': 'image/jpeg' } });
globalThis.fetch = async (input, init) => {
  seen.push({ url: String(input), init });
  return handler(String(input), init);
};

const IMG = 'https://lh3.googleusercontent.com/pw/AP1GczABCDEFGHIJKLMNOPQRSTUV=w480-h320-no?authuser=0';
const call = (path, headers = {}, method = 'GET') =>
  worker.fetch(new Request('https://proxy.example.workers.dev' + path, { method, headers }), env);
const viaProxy = (u, headers = {}) => call('/p?u=' + encodeURIComponent(u), { 'X-Access-Key': KEY, ...headers });

console.log('Setup and auth');
{
  const r0 = await worker.fetch(new Request('https://x.workers.dev/ping'), {});
  check('no ACCESS_KEY -> 500', r0.status === 500);
  const r1 = await worker.fetch(new Request('https://x.workers.dev/ping'), { ACCESS_KEY: 'short' });
  check('short ACCESS_KEY -> 500', r1.status === 500);
  check('no key header -> 401', (await call('/ping')).status === 401);
  check('wrong key -> 401', (await call('/ping', { 'X-Access-Key': 'nope-nope-nope-nope' })).status === 401);
  const ok = await call('/ping', { 'X-Access-Key': KEY });
  check('right key on /ping -> 200 ok', ok.status === 200 && (await ok.text()) === 'ok');
  check('key in URL is NOT accepted', (await call('/ping?k=' + KEY)).status === 401);
  check('unknown path -> 404', (await call('/nope', { 'X-Access-Key': KEY })).status === 404);
  check('POST refused', (await call('/p?u=x', { 'X-Access-Key': KEY }, 'POST')).status === 405);
  seen.length = 0;
  await call('/p?u=' + encodeURIComponent(IMG));
  check('unauthenticated request never reaches Google', seen.length === 0);
}

console.log('Host allowlist');
{
  const bad = [
    'https://evil.example.com/pw/x',
    'http://lh3.googleusercontent.com/pw/x',
    'https://googleusercontent.com.evil.com/pw/x',
    'https://notgoogleusercontent.com/pw/x',
    'https://lh3.googleusercontent.com@evil.com/pw/x',
    'https://user:pw@lh3.googleusercontent.com/pw/x',
    'https://lh3.googleusercontent.com:8443/pw/x',
    'https://169.254.169.254/latest/meta-data',
    'ftp://lh3.googleusercontent.com/x',
    'not a url',
    '',
  ];
  seen.length = 0;
  for (const u of bad) {
    const r = await viaProxy(u);
    check('refused: ' + (u || '(empty)'), r.status === 400, String(r.status));
  }
  check('refused addresses were never fetched', seen.length === 0);

  for (const u of [
    IMG,
    'https://lh5.googleusercontent.com/a/abc',
    'https://photos.fife.usercontent.google.com/pw/abc',
    'https://lh3.ggpht.com/abc',
  ]) {
    handler = async () => new Response('x', { status: 200, headers: { 'Content-Type': 'image/webp' } });
    const r = await viaProxy(u);
    check('allowed: ' + u.slice(0, 55), r.status === 200, String(r.status));
  }
}

console.log('What is sent to Google');
{
  handler = async () => new Response('x', { status: 200, headers: { 'Content-Type': 'image/jpeg' } });
  seen.length = 0;
  await viaProxy(IMG, { Cookie: 'SID=secret', Authorization: 'Bearer abc', Range: 'bytes=0-99' });
  const h = seen[0].init.headers;
  check('fetches the exact Google address', seen[0].url === IMG, seen[0].url);
  check('cookie not forwarded', !('Cookie' in h) && !('cookie' in h));
  check('authorization not forwarded', !('Authorization' in h));
  check('access key not forwarded', !JSON.stringify(h).includes(KEY));
  check('Range forwarded', h.Range === 'bytes=0-99');
  seen.length = 0;
  await viaProxy(IMG, { Range: 'bytes=0-1;evil' });
  check('odd Range value dropped', !('Range' in seen[0].init.headers));
}

console.log('What comes back');
{
  handler = async () =>
    new Response('bytes', {
      status: 206,
      headers: {
        'Content-Type': 'video/mp4',
        'Content-Range': 'bytes 0-4/100',
        'Set-Cookie': 'NID=tracker',
        'X-Evil': '1',
        'Content-Disposition': 'attachment; filename="a.mp4"',
      },
    });
  const r = await viaProxy('https://video-downloads.googleusercontent.com/AD_abc', { Range: 'bytes=0-4' });
  check('206 passed through', r.status === 206);
  check('Content-Range passed', r.headers.get('Content-Range') === 'bytes 0-4/100');
  check('filename passed', (r.headers.get('Content-Disposition') || '').includes('a.mp4'));
  check('Set-Cookie stripped', !r.headers.get('Set-Cookie'));
  check('unknown headers stripped', !r.headers.get('X-Evil'));
  check('device-cache header set', (r.headers.get('Cache-Control') || '').includes('max-age=604800'));
  check('body passed', (await r.text()) === 'bytes');

  handler = async () => new Response('<html>hi</html>', { status: 200, headers: { 'Content-Type': 'text/html' } });
  check('HTML refused', (await viaProxy(IMG)).status === 502);
  handler = async () => new Response('x', { status: 200, headers: { 'Content-Type': 'application/zip' } });
  check('zip allowed (album download)', (await viaProxy(IMG)).status === 200);
  handler = async () => new Response('gone', { status: 404 });
  check('404 from Google -> 404', (await viaProxy(IMG)).status === 404);
  handler = async () => new Response('err', { status: 500 });
  check('500 from Google -> 502', (await viaProxy(IMG)).status === 502);
}

console.log('Redirects');
{
  handler = async (u) =>
    u.includes('start')
      ? new Response(null, { status: 302, headers: { Location: 'https://lh4.googleusercontent.com/pw/final' } })
      : new Response('x', { status: 200, headers: { 'Content-Type': 'image/png' } });
  check('redirect inside Google followed', (await viaProxy('https://lh3.googleusercontent.com/start')).status === 200);
  handler = async () => new Response(null, { status: 302, headers: { Location: 'https://evil.example.com/x' } });
  check('redirect to other host refused', (await viaProxy(IMG)).status === 400);
  handler = async () => new Response(null, { status: 302, headers: { Location: 'https://lh3.googleusercontent.com/loop' } });
  check('redirect loop stops', (await viaProxy(IMG)).status === 502);
}

console.log('Shared-album viewer');
{
  const TOKEN_A = 'AF1QipAAAAAAAAAAAAAAAAAAAAAA';
  const TOKEN_B = 'AF1QipBBBBBBBBBBBBBBBBBBBBBB';
  const albumHtml =
    '<html><head><meta property="og:title" content="Trip &amp; fun"></head><body>' +
    '"https://lh3.googleusercontent.com/pw/' + TOKEN_A + '" "https://lh3.googleusercontent.com/pw/' + TOKEN_B + '"' +
    ' "https://lh3.googleusercontent.com/pw/' + TOKEN_A + '"</body></html>';

  const page = await call('/');
  const pageText = await page.text();
  check('viewer page served', page.status === 200 && pageText.includes('<!doctype html>'));
  check('page alone does not sign in', !page.headers.get('Set-Cookie'));
  const csp = page.headers.get('Content-Security-Policy') || '';
  check('page may only load images from itself', csp.includes("img-src 'self'") && !csp.includes('googleusercontent'));

  const wrong = await call('/', { 'X-Access-Key': 'nope-nope-nope-nope' });
  check('wrong header key does not sign in', !wrong.headers.get('Set-Cookie'));
  const signed = await call('/', { 'X-Access-Key': KEY });
  const cookieHdr = signed.headers.get('Set-Cookie') || '';
  check('right header key signs in (cookie set)', /pp_session=[0-9a-f]{64}/.test(cookieHdr) && cookieHdr.includes('HttpOnly'));
  check('cookie does not contain the key', !cookieHdr.includes(KEY));
  const cookie = cookieHdr.split(';')[0];

  check('album API needs sign-in', (await call('/api/album?link=x')).status === 401);
  check('image API needs sign-in', (await call('/img?u=x')).status === 401);

  const badLogin = await worker.fetch(
    new Request('https://proxy.example.workers.dev/login', { method: 'POST', body: JSON.stringify({ key: 'nope-nope-nope-nope' }) }),
    env
  );
  check('wrong login -> 401', badLogin.status === 401 && !badLogin.headers.get('Set-Cookie'));
  const goodLogin = await worker.fetch(
    new Request('https://proxy.example.workers.dev/login', { method: 'POST', body: JSON.stringify({ key: KEY }) }),
    env
  );
  check('right login -> cookie', goodLogin.status === 200 && (goodLogin.headers.get('Set-Cookie') || '').startsWith('pp_session='));
  check('cookie works', (await call('/api/me', { Cookie: cookie })).status === 200);

  seen.length = 0;
  handler = async () => new Response(albumHtml, { status: 200, headers: { 'Content-Type': 'text/html' } });
  const al = await call('/api/album?link=' + encodeURIComponent('https://photos.google.com/share/AF1QipX?key=abc'), { Cookie: cookie });
  const alJson = await al.json();
  check('album parsed, duplicates removed', al.status === 200 && alJson.count === 2 && alJson.title === 'Trip & fun');
  check('picture links point to this Worker only', alJson.items.every((i) => i.thumb.startsWith('/img?') && i.full.startsWith('/img?')));
  check('no cookies sent to Google', seen.every((x) => !(x.init && x.init.headers && (x.init.headers.Cookie || x.init.headers.cookie))));

  check('non-album link refused', (await call('/api/album?link=' + encodeURIComponent('https://evil.example.com/share/x'), { Cookie: cookie })).status === 400);
  check('private library link refused', (await call('/api/album?link=' + encodeURIComponent('https://photos.google.com/photo/AF1Qip'), { Cookie: cookie })).status === 400);

  handler = async () => new Response('x', { status: 200, headers: { 'Content-Type': 'image/jpeg' } });
  seen.length = 0;
  const base = 'https://lh3.googleusercontent.com/pw/' + TOKEN_A;
  const im = await call('/img?m=t&u=' + encodeURIComponent(base), { Cookie: cookie });
  check('thumbnail fetched', im.status === 200 && seen.length === 1 && seen[0].url === base + '=w480-h480-c');
  const imF = await call('/img?m=f&u=' + encodeURIComponent(base), { Cookie: cookie });
  check('full size fetched', imF.status === 200 && seen[seen.length - 1].url === base + '=w2048');
  check('other Google picture path refused', (await call('/img?u=' + encodeURIComponent('https://lh3.googleusercontent.com/a/private'), { Cookie: cookie })).status === 400);
  check('other host refused', (await call('/img?u=' + encodeURIComponent('https://evil.example.com/pw/' + TOKEN_A), { Cookie: cookie })).status === 400);
}

globalThis.fetch = realFetch;
console.log('\n' + passed + ' passed, ' + failed + ' failed');
process.exit(failed ? 1 : 0);
