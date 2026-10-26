# Kosher Photos

A small Android app that opens the Google Photos website (photos.google.com) in its own window, with the "create album" / print-store pages blocked.

## Get the APK (no computer needed)

1. Open this repo on github.com and tap the **Actions** tab.
2. Open the latest **Build APK** run (green check = success).
3. Scroll to **Artifacts** and download **gphotos-viewer-debug-apk**.
4. Unzip it and install `app-debug.apk`.

To rebuild any time: Actions > Build APK > Run workflow.

## Stream via proxy (keep googleusercontent.com blocked in NextDNS)

Google Photos loads its pictures from `googleusercontent.com`, the same address Google Maps uses for place photos. If you block that address in NextDNS, Google Photos goes blank too.

With **Stream via proxy** on, the app opens a small **album viewer on your own Cloudflare Worker**. You paste a *link-shared* Google Photos album, the Worker fetches the pictures (no Google sign-in) and serves them from your Worker address. The phone only talks to your Worker, so NextDNS can keep `googleusercontent.com` blocked for every other app.

**Works:** albums shared by link (`photos.app.goo.gl/...` or `photos.google.com/share/...`), including friends' albums.
**Does not work:** your private library (Google needs your sign-in for those pictures; the proxy never sends cookies). To see your own photos, put them in an album on an unfiltered device and share it by link.
**Untested by me against live Google:** whether Google's album pages still list their pictures the way the viewer expects. If an album shows "No photos found", tell me.

### Set up the proxy (free, about 10 minutes, works from a phone)

1. Make a free account at https://dash.cloudflare.com/sign-up.
2. **Workers & Pages** > **Create** > choose **Start with Hello World!** (the Workers option, not the static-file upload) > name it > **Deploy**.
3. Press **Edit code**, delete everything, paste the whole of `worker/worker.js`, press **Deploy**.
4. **Settings** > **Variables and Secrets** > **Add** > type **Secret**, name `ACCESS_KEY`, value a long key you invent (12+ characters, not used anywhere else). Save and deploy again. Write the key down.
5. Copy your Worker address (like `https://photos-proxy.yourname.workers.dev`).

Cloudflare renames menu items now and then, so labels may differ slightly.

### Turn it on in the app

1. Long-press the Kosher Photos icon and tap **Proxy settings**. If your launcher does not show it, ask me and I will add a button inside the app.
2. Enter the proxy address and the access key, tap **Test connection** (it should say "Connected"), switch **Stream via proxy** on, tap **Save**.
3. The app reloads into the album viewer (it signs in with your key automatically). Paste an album link and tap Open.
4. Switch the proxy off in Proxy settings to go back to normal Google Photos.

### Check it really works

In NextDNS > Logs, open Google Photos and scroll. You should see lookups for your `workers.dev` address and **none** for `googleusercontent.com` coming from this app. Then add `*.googleusercontent.com` back to your denylist and check Google Photos still loads.

If pictures go blank after you block it, the logs will show which other Google address the page needed. Tell me and I will add it to the list (`PROXIED_HOSTS` in `ProxyFetcher.java` and `UPSTREAM_ALLOWED` in `worker/worker.js`).

### Limits and cost

- Cloudflare free plan: 100,000 requests a day, 1,000 a minute. Each picture is one request. The phone keeps a 150 MB cache, so pictures you already saw are not requested again.
- Streaming a lot of video can reach the daily limit. The paid plan is about $5 a month. Check Cloudflare's pricing page, it can change.
- With the proxy on, a failed proxy means a blank picture. The app never quietly goes straight to Google, because that would defeat the block.

### Safety notes

- The proxy only fetches Google picture hosts, only over https, and only picture, video or zip files. It rejects everything else.
- No Google cookies or sign-in information are sent through the proxy. Only the access key is sent, in a header.
- The access key is stored in the app's private storage and left out of Android backups.
- Anyone with your proxy address **and** access key can use your proxy for Google pictures. Keep the key private. To change it, set `ACCESS_KEY` again in Cloudflare and update the app.

### Check the proxy code

`node worker/test.mjs` runs 65 checks on the Worker (needs Node 18 or newer). Google is replaced by fake answers, so nothing leaves your computer.

## Changing which pages are blocked

Edit `BLOCKED_URL_CONTAINS` in
`app/src/main/java/com/example/gphotosviewer/MainActivity.java`.
Any Google Photos address containing one of those pieces of text is refused.
