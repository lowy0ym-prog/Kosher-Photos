package com.example.gphotosviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> filePathCallback;

    // Pull-down-to-refresh: the page tells us (through KP.setTop) whether the
    // spot you touched is scrolled to the very top.
    private volatile boolean touchAtTop = false;
    private float pullStartY = -1f;
    private boolean pullTracking = false;
    private boolean pulling = false;
    private boolean refreshing = false;
    private View pullIndicator;

    public class TopBridge {
        @JavascriptInterface
        public void setTop(boolean atTop) {
            touchAtTop = atTop;
        }
    }

    // Full-screen video support
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

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
    private static final String[] BLOCKED_URL_CONTAINS = {
        "/create",
        "printstore",
        "/assistant",
        "/gift",
        "/photobooks"
    };

    // Script that hides "Get the app" / "Open in app" / "You're missing out"
    // banners, plus menu items such as Print store, Settings, Help & Feedback,
    // App downloads, Get more storage and the Privacy / Terms / Policy links. It looks for that wording on the page (not for Google's
    // internal names, which change often) and hides the banner around it.
    // Best effort: if Google changes the wording, a banner can come back.
    private static final String HIDE_BANNERS_JS =
        "(function(){"
        + "if(window.__kpBannerHider){window.__kpBannerHider();return;}"
        + "document.addEventListener('touchstart',function(e){"
        + "  var el=e.target,top=true;"
        + "  while(el&&el!==document){if(el.scrollTop>0){top=false;break;}el=el.parentElement;}"
        + "  if(window.scrollY>0)top=false;"
        + "  if(/\\/photo\\//.test(location.pathname))top=false;"
        + "  if(window.visualViewport&&window.visualViewport.scale>1.01)top=false;"
        + "  try{KP.setTop(top);}catch(x){}"
        + "},{passive:true,capture:true});"
        + "var re=/(get the app|open (in )?(the )?(google photos )?app|you.?re missing out|use the app|install the app)/i;"
        + "function hideFor(node){"
        + "  var el=node.parentElement,depth=0,fallback=null;"
        + "  while(el&&el!==document.body&&depth<8){"
        + "    var cs=getComputedStyle(el),r=el.getBoundingClientRect();"
        + "    var small=r.height<window.innerHeight*0.4;"
        + "    if(small&&(cs.position==='fixed'||cs.position==='sticky')){el.style.display='none';return;}"
        + "    if(!fallback&&small&&r.width>=window.innerWidth*0.6&&r.height>=30&&r.height<=260){fallback=el;}"
        + "    el=el.parentElement;depth++;"
        + "  }"
        + "  if(fallback){fallback.style.display='none';}"
        + "}"
        + "var labels=['help & feedback','settings','app downloads','get more storage','print store','print','order canvas print','order photo prints','facebook','x','open in app','privacy','terms','policy'];"
        + "var tops=[];"
        + "function vis(e){return e.getClientRects().length>0;}"
        + "function isLine(e){return !!e&&e.childElementCount===0&&(e.innerText||'')===''&&e.offsetHeight>0&&e.offsetHeight<=3;}"
        + "function sib(e,d){var s=d>0?e.nextElementSibling:e.previousElementSibling;"
        + "  while(s&&!vis(s)){s=d>0?s.nextElementSibling:s.previousElementSibling;}return s;}"
        + "function hideLabel(n){"
        + "  var el=n.parentElement;if(!el)return;"
        + "  var t=el.closest('a,button,[role=button],[role=link],[role=menuitem],[role=tab],li')||el;"
        + "  t.style.display='none';"
        + "  var p=t.parentElement,k=0;"
        + "  while(p&&p!==document.body&&k<4){"
        + "    if((p.innerText||'').replace(/[^A-Za-z0-9]/g,'')!=='')break;"
        + "    var any=false,q=p.querySelectorAll('img,video,canvas,svg,input,a,button,[role=button],[role=link]');"
        + "    for(var i=0;i<q.length;i++){if(vis(q[i])){any=true;break;}}"
        + "    if(any)break;"
        + "    p.style.display='none';t=p;p=p.parentElement;k++;"
        + "  }"
        + "  tops.push(t);"
        + "}"
        + "function fixLines(){"
        + "  for(var i=0;i<tops.length;i++){"
        + "    var a=sib(tops[i],-1),b=sib(tops[i],1);"
        + "    if(isLine(a)&&isLine(b)){b.style.display='none';}"
        + "  }"
        + "  tops=[];"
        + "}"
        + "function scan(){"
        + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;"
        + "  while((n=w.nextNode())){"
        + "    var t=n.nodeValue;"
        + "    if(t&&labels.indexOf(t.replace(/\\s+/g,' ').trim().toLowerCase())>=0){hideLabel(n);continue;}"
        + "    if(t&&t.length<120&&re.test(t)){"
        + "      var p=n.parentElement;"
        + "      if(p&&p.tagName!=='SCRIPT'&&p.tagName!=='STYLE'&&p.offsetParent!==null||(p&&getComputedStyle(p).position==='fixed')){hideFor(n);}"
        + "    }"
        + "  }"
        + "  fixLines();"
        + "}"
        + "var timer=null;"
        + "function later(){if(timer)return;timer=setTimeout(function(){timer=null;try{scan();}catch(e){}},600);}"
        + "window.__kpBannerHider=later;"
        + "new MutationObserver(later).observe(document.body,{childList:true,subtree:true});"
        + "later();"
        + "})();";

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

    private void showPull(float offset, float density, boolean animate) {
        float trigger = 64f * density;
        float size = 40f * density;
        float frac = Math.min(1f, offset / trigger);
        pullIndicator.setVisibility(View.VISIBLE);
        float y = offset / 2f - size / 2f;
        if (animate) {
            webView.animate().translationY(offset).setDuration(200).start();
            pullIndicator.animate().translationY(y).alpha(1f)
                    .scaleX(1f).scaleY(1f).setDuration(200).start();
        } else {
            webView.animate().cancel();
            pullIndicator.animate().cancel();
            webView.setTranslationY(offset);
            pullIndicator.setTranslationY(y);
            pullIndicator.setAlpha(frac);
            pullIndicator.setScaleX(0.6f + 0.4f * frac);
            pullIndicator.setScaleY(0.6f + 0.4f * frac);
        }
    }

    private void hidePull() {
        webView.animate().translationY(0f).setDuration(200).start();
        pullIndicator.animate().alpha(0f).setDuration(200)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!refreshing) pullIndicator.setVisibility(View.INVISIBLE);
                    }
                }).start();
    }

    private void endPull(boolean refresh) {
        float density = getResources().getDisplayMetrics().density;
        if (refresh) {
            refreshing = true;
            webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            showPull(64f * density, density, true);
            webView.reload();
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    finishRefresh();
                }
            }, 12000); // safety: never stay stuck
        } else {
            hidePull();
        }
    }

    private void finishRefresh() {
        if (!refreshing) return;
        refreshing = false;
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        hidePull();
    }

    // Pinch-zoom of the whole page is allowed only while one photo is open,
    // so the photo grid and menus never zoom by accident.
    private void updateZoom(String url) {
        boolean photo = url != null && url.contains("/photo/");
        webView.getSettings().setSupportZoom(photo);
        webView.getSettings().setBuiltInZoomControls(photo);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
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

    private void injectBannerHider(WebView view) {
        view.evaluateJavascript(HIDE_BANNERS_JS, null);
    }

    private void leaveFullScreenVideo() {
        if (customView == null) return;
        FrameLayout decor = (FrameLayout) getWindow().getDecorView();
        decor.removeView(customView);
        customView = null;
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        webView.setVisibility(View.VISIBLE);
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
        // Pinch-to-zoom like a normal browser (the zoom stays when you let go).
        // (turned on only while a single photo is open, see updateZoom)
        updateZoom(null);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setAllowFileAccess(false);
        // Let videos start when you tap them (WebView otherwise can refuse).
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // Cache: pages and photos already seen are kept and reused. With no
        // internet the saved copy is shown instead of an error.
        // Saved photos/files are reused even when Google marks them as
        // "expired"; only what is missing is downloaded. Pull-down refresh
        // switches to fresh downloads for that one reload.
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        webView.addJavascriptInterface(new TopBridge(), "KP");

        // Pull down from the top of the photos to refresh: the page follows
        // your finger down, a round spinner appears, and letting go reloads.
        // This only watches the touches; it never blocks normal scrolling.
        pullIndicator = findViewById(R.id.pull_indicator);
        ProgressBar spinner = (ProgressBar) findViewById(R.id.pull_spinner);
        spinner.setIndeterminateTintList(
                ColorStateList.valueOf(getColor(R.color.pull_spinner)));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final float density = getResources().getDisplayMetrics().density;
        final float slop = 12f * density;
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                try {
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        pullStartY = ev.getRawY();
                        pullTracking = !refreshing;
                        pulling = false;
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (!pullTracking) return false;
                        if (ev.getPointerCount() > 1) {
                            endPull(false);
                            pullTracking = false;
                            return false;
                        }
                        float dy = ev.getRawY() - pullStartY;
                        if (!pulling) {
                            if (dy < -slop) {
                                pullTracking = false; // scrolling up: not a pull
                            } else if (dy > slop && touchAtTop
                                    && !webView.canScrollVertically(-1)) {
                                pulling = true;
                            }
                        }
                        if (pulling) {
                            float offset = Math.min((dy - slop) * 0.5f, 110f * density);
                            if (offset < 0) offset = 0;
                            showPull(offset, density, false);
                        }
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        if (pulling) {
                            endPull(action == MotionEvent.ACTION_UP
                                    && webView.getTranslationY() >= 64f * density);
                        }
                        pullTracking = false;
                        pulling = false;
                    }
                } catch (Exception ignored) {
                }
                return false; // never consume: scrolling stays normal
            }
        });

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
                updateZoom(url);
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
                finishRefresh();
                if (url != null && url.contains("photos.google.com")) {
                    injectBannerHider(view);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // "Add photos" / any upload button: open the phone's photo picker.
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

            // Full-screen video (the full-screen button on a playing video).
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                webView.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onHideCustomView() {
                leaveFullScreenVideo();
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
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // keep the sign-in saved
    }

    // Back button. On a single photo it goes back normally. On any other
    // page it skips past photo pages (so backing out of an album does not
    // reopen the photo you just left) and leaves the app when there is
    // no earlier page to return to.
    private boolean goBackSmart() {
        String current = webView.getUrl();
        if (current != null && current.contains("/photo/")) {
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            return false;
        }
        WebBackForwardList list = webView.copyBackForwardList();
        int idx = list.getCurrentIndex();
        for (int i = idx - 1; i >= 0; i--) {
            WebHistoryItem item = list.getItemAtIndex(i);
            String u = item == null ? null : item.getUrl();
            if (u == null || !u.contains("photos.google.com")) continue;
            if (u.contains("/photo/") || u.equals(current)) continue;
            webView.goBackOrForward(i - idx);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            leaveFullScreenVideo();
        } else if (!goBackSmart()) {
            super.onBackPressed(); // nothing sensible to go back to: leave the app
        }
    }
}
