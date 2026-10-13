package com.example.gphotosviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> filePathCallback;

    // Main Google Photos page (blocked pages send you back here).
    private static final String HOME_URL = "https://photos.google.com/";

    // The app opens here first. If you are not signed in, Google shows its
    // sign-in screen; once signed in (the login is remembered), it goes
    // straight on to Google Photos.
    private static final String START_URL =
            "https://accounts.google.com/ServiceLogin?service=lh2"
            + "&continue=https%3A%2F%2Fphotos.google.com%2F";

    // ---------------------------------------------------------------
    // ONLY these sites can be opened as a page. Everything else is refused,
    // so the app cannot be used to browse the rest of the web.
    //   photos.google.com        - Google Photos itself
    //   accounts.google.com etc. - Google sign-in
    //   googleusercontent.com    - where photo downloads come from
    // If sign-in ever breaks because Google needs another address, it
    // would have to be added here.
    // ---------------------------------------------------------------
    private static final String[] ALLOWED_HOSTS = {
        "photos.google.com",
        "accounts.google.com",
        "accounts.youtube.com",
        "consent.google.com",
        "googleusercontent.com",
        "usercontent.google.com"
    };

    // Inside Google Photos, any address containing one of these pieces of
    // text is refused (the "create album" / print-store pages).
    // I could not verify Google's real addresses for these pages. If the
    // sample page still shows up, open photos.google.com in Chrome, start
    // creating an album, copy a distinctive piece of the address bar and add
    // it to this list.
    private static final String[] BLOCKED_URL_CONTAINS = {
        "/create",
        "printstore",
        "/assistant",
        "/gift",
        "/photobooks"
    };

    private boolean isAllowed(String url) {
        if (url == null) return false;
        if (url.equals("about:blank")) return true;
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    private boolean isBlockedPage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (!lower.contains("photos.google.com")) return false;
        for (String piece : BLOCKED_URL_CONTAINS) {
            if (lower.contains(piece)) return true;
        }
        return false;
    }

    private void toast(String message) {
        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
    }

    // Hide the page right away (so a blocked page never stays on screen
    // while it loads) and go back to the main Google Photos page.
    private void goHome(WebView view) {
        view.stopLoading();
        view.setVisibility(View.INVISIBLE);
        view.loadUrl(HOME_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = (WebView) findViewById(R.id.webview);
        progress = (ProgressBar) findViewById(R.id.progress);

        // Cookies on so your login session is remembered between launches.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(false);
        // Zoom is handled by the Google Photos page itself. Turning off the
        // WebView's own zoom avoids it fighting with swipe gestures.
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setBuiltInZoomControls(false);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setAllowFileAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!isAllowed(url)) {
                    toast("Only Google Photos can be opened in this app.");
                    return true; // refuse
                }
                if (isBlockedPage(url)) {
                    toast("This page is disabled in this app.");
                    return true; // refuse
                }
                return false;
            }

            // Google Photos is a single-page app: many screens change the
            // address without a normal page load, so also check here.
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (url == null) return;
                if (!isAllowed(url)) {
                    toast("Only Google Photos can be opened in this app.");
                    goHome(view);
                } else if (isBlockedPage(url)) {
                    toast("This page is disabled in this app.");
                    goHome(view);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                view.setVisibility(View.VISIBLE);
            }
        });

        // "Add photos" / any upload button: open the phone's photo picker.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("No photo picker found on this phone.");
                    return false;
                }
                return true;
            }
        });

        // Download button: save the file to the phone's Downloads folder.
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition,
                                        String mimetype, long contentLength) {
                if (url == null || !url.startsWith("https")) {
                    toast("This download type is not supported.");
                    return;
                }
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setTitle(fileName);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager manager =
                            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    manager.enqueue(request);
                    toast("Downloading " + fileName);
                } catch (Exception e) {
                    toast("Download failed.");
                }
            }
        });

        webView.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results =
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
