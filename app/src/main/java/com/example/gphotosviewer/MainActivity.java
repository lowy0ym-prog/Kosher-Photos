package com.example.gphotosviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar progress;

    // Main Google Photos page (where blocked pages send you back to).
    private static final String HOME_URL = "https://photos.google.com/";

    // The app opens here first. If you are not signed in, Google shows its
    // sign-in screen; once signed in (the login is remembered), it goes
    // straight on to Google Photos.
    private static final String START_URL =
            "https://accounts.google.com/ServiceLogin?service=lh2"
            + "&continue=https%3A%2F%2Fphotos.google.com%2F";

    // Blocking only applies on the Google Photos site itself, so Google's
    // sign-in pages are never affected by these patterns.
    private static final String PHOTOS_HOST = "photos.google.com";

    // If a Google Photos address contains any of these pieces of text, the
    // app refuses to open it and returns you to the main page.
    // I could not verify Google's real "create album" / print-store addresses
    // (no logged-in session to inspect). If the sample page still shows up,
    // open photos.google.com in Chrome, start creating an album, copy a
    // distinctive piece of the address bar and add it to this list.
    private static final String[] BLOCKED_URL_CONTAINS = {
        "/create",
        "printstore",
        "/assistant",
        "/gift",
        "/photobooks"
    };

    private boolean isBlocked(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (!lower.contains(PHOTOS_HOST)) return false;
        for (String piece : BLOCKED_URL_CONTAINS) {
            if (lower.contains(piece)) return true;
        }
        return false;
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
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isBlocked(url)) {
                    Toast.makeText(MainActivity.this,
                            "This page is disabled in this app.",
                            Toast.LENGTH_SHORT).show();
                    return true; // cancel navigation
                }
                return false;
            }

            // Google Photos is a single-page app: many screens change the
            // address without a normal page load, so also check here.
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (isBlocked(url)) {
                    Toast.makeText(MainActivity.this,
                            "This page is disabled in this app.",
                            Toast.LENGTH_SHORT).show();
                    view.loadUrl(HOME_URL);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });

        webView.loadUrl(START_URL);
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
