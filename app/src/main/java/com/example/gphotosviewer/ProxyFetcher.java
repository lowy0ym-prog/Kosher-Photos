package com.example.gphotosviewer;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "Stream via proxy": when Google Photos asks for a picture from a Google
 * picture host, the app asks your own proxy (the Cloudflare Worker) instead.
 * The page never contacts those Google hosts itself, so a DNS filter can keep
 * them blocked for every other app (for example Google Maps).
 *
 * No cookies or sign-in information are sent to the proxy, only the access key.
 */
final class ProxyFetcher {

    // Pictures and videos from these hosts (and their subdomains) go through
    // the proxy. Keep in step with UPSTREAM_ALLOWED in worker/worker.js.
    private static final String[] PROXIED_HOSTS = {
        "googleusercontent.com",
        "usercontent.google.com",
        "ggpht.com"
    };

    interface ErrorSink {
        void onError(String message);
    }

    private ProxyFetcher() {
    }

    static boolean shouldProxy(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String d : PROXIED_HOSTS) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /** The address on the proxy that serves the given Google address. */
    static String proxyUrl(ProxyConfig cfg, String target) throws UnsupportedEncodingException {
        return cfg.base + "/p?u=" + URLEncoder.encode(target, "UTF-8");
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static WebResourceResponse failure(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason,
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    /** Fetches one picture through the proxy. Never falls back to the blocked host. */
    static WebResourceResponse fetch(ProxyConfig cfg, WebResourceRequest req, ErrorSink sink) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(proxyUrl(cfg, req.getUrl().toString())).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("X-Access-Key", cfg.key);

            Map<String, String> in = req.getRequestHeaders();
            String range = header(in, "Range");
            if (range != null) c.setRequestProperty("Range", range);
            String accept = header(in, "Accept");
            if (accept != null) c.setRequestProperty("Accept", accept);

            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                if (code == 401) {
                    sink.onError("Proxy: wrong access key. Check Proxy settings.");
                } else if (code == 500) {
                    sink.onError("Proxy is not set up (missing ACCESS_KEY secret).");
                } else if (code != 404) {
                    sink.onError("Proxy error (" + code + ").");
                }
                c.disconnect();
                return failure(code == 404 ? 404 : 502, code == 404 ? "Not Found" : "Bad Gateway");
            }

            String mime = "application/octet-stream";
            String encoding = null;
            String ct = c.getContentType();
            if (ct != null) {
                String[] parts = ct.split(";");
                mime = parts[0].trim();
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                        encoding = p.substring(8).trim();
                    }
                }
            }

            Map<String, String> out = new HashMap<String, String>();
            String contentRange = c.getHeaderField("Content-Range");
            if (contentRange != null) out.put("Content-Range", contentRange);
            String acceptRanges = c.getHeaderField("Accept-Ranges");
            if (acceptRanges != null) out.put("Accept-Ranges", acceptRanges);

            // The page may read the picture with a script (not just show it),
            // which needs the browser's cross-site permission headers.
            String origin = header(in, "Origin");
            if (origin != null) {
                out.put("Access-Control-Allow-Origin", origin);
                out.put("Access-Control-Allow-Credentials", "true");
                out.put("Vary", "Origin");
            } else {
                out.put("Access-Control-Allow-Origin", "*");
            }

            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) {
                reason = code == 206 ? "Partial Content" : "OK";
            }
            InputStream body = c.getInputStream();
            return new WebResourceResponse(mime, encoding, code, reason, out, body);
        } catch (IOException e) {
            sink.onError("Cannot reach your proxy. Check Proxy settings and your internet.");
            return failure(504, "Gateway Timeout");
        } catch (Exception e) {
            return failure(502, "Bad Gateway");
        }
    }
}
