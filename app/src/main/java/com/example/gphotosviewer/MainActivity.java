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
import android.os.Message;
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
    private long lastConsoleToast = 0;
    private float downX = 0f;
    private long downTime = 0L;
    private boolean edgeStart = false;
    private boolean drawerDone = false;

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
    // banners, plus menu items such as Print store, Help & Feedback,
    // App downloads, Get more storage and the Privacy / Terms / Policy links. It looks for that wording on the page (not for Google's
    // internal names, which change often) and hides the banner around it.
    // Best effort: if Google changes the wording, a banner can come back.
    private static final String HIDE_BANNERS_JS =
        "(function(){"
        + "if(window.__kpBannerHider){window.__kpBannerHider();return;}"
        + "window.__kpDrawer=function(mode){"
        + "  var open=false,w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;"
        + "  while((n=w.nextNode())){"
        + "    if((n.nodeValue||'').trim().toLowerCase()==='storage'&&n.parentElement){"
        + "      var r=n.parentElement.getBoundingClientRect();"
        + "      if(r.width>0&&r.right>8&&r.left<window.innerWidth*0.8){open=true;break;}"
        + "    }"
        + "  }"
        + "  if(mode==='open'&&!open){"
        + "    var b=document.querySelector('[aria-label=\"Main menu\"],[aria-label=\"Menu\"],[aria-label=\"Open menu\"],[aria-label=\"Navigation menu\"]');"
        + "    if(b)b.click();"
        + "  }"
        + "  if(mode==='close'&&open){"
        + "    var el=document.elementFromPoint(window.innerWidth-6,window.innerHeight/2);"
        + "    if(el&&!el.closest('a[href*=\"/photo/\"]'))el.click();"
        + "  }"
        + "};"
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
        + "var labels=['help & feedback','app downloads','get more storage','print store','print','order canvas print','order photo prints','create new photo book','from other places','wall art','view all products','facebook','x','open in app','privacy','terms','policy'];"
        + "var tops=[];"
        + "function vis(e){return e.getClientRects().length>0;}"
        + "function isLine(e){return !!e&&(e.innerText||'')===''&&e.offsetHeight>0&&e.offsetHeight<=3;}"
        + "function sib(e,d){var s=d>0?e.nextElementSibling:e.previousElementSibling;"
        + "  while(s&&!vis(s)){s=d>0?s.nextElementSibling:s.previousElementSibling;}return s;}"
        + "function norm(x){return (x||'').replace(/\\s+/g,' ').trim().toLowerCase();}"
        + "function core(x){return (x||'').replace(/\\s+/g,' ').trim().split(' ').filter(function(w){return !/^[a-z][a-z0-9_]*$/.test(w);}).join(' ').toLowerCase();}"
        + "var INT='a,button,[role=button],[role=link],[role=menuitem],[role=option],[role=tab]';"
        + "function icount(e){return e.querySelectorAll(INT).length+(e.matches(INT)?1:0);}"
        + "function other(e,lab){return (e.innerText||'').toLowerCase().replace(lab,'').replace(/\\s+/g,'').length;}"
        + "function hideLabel(n){"
        + "  var el=n.parentElement;if(!el)return;"
        + "  var lab=norm(n.nodeValue);"
        + "  var t=el;"
        + "  while(t.parentElement&&t.parentElement!==document.body){"
        + "    var q=t.parentElement;"
        + "    if(q.getBoundingClientRect().height>90)break;"
        + "    if(icount(q)>1)break;"
        + "    if(other(q,lab)>40)break;"
        + "    t=q;"
        + "  }"
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
        + "function dedupeAbove(el){"
        + "  for(var k=0;k<6&&el&&el!==document.body;k++){"
        + "    var a=sib(el,-1);"
        + "    if(isLine(a)){var b=sib(a,-1);if(isLine(b)){a.style.display='none';return;}}"
        + "    el=el.parentElement;"
        + "  }"
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
        + "    if(t&&t.replace(/\\s+/g,' ').trim().toLowerCase()==='storage'&&n.parentElement){dedupeAbove(n.parentElement);}"
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
        // Highlight video, Animation and Collage use "/create" addresses too;
        // those are allowed.
        boolean allowedCreate = lower.contains("/movies/")
                || lower.contains("/animation")
                || lower.contains("/collage");
        for (String piece : BLOCKED_URL_CONTAINS) {
            if (piece.equals("/create") && allowedCreate) continue;
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

    // Short form of an address for messages, e.g. "/create/album".
    private String shortPath(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return "";
            return path.length() > 60 ? path.substring(0, 60) : path;
        } catch (Exception e) {
            return "";
        }
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
        // Chosen photos for upload are read through the phone's file system;
        // the page itself can never open local files (no file:// access from pages).
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        // Let videos start when you tap them (WebView otherwise can refuse).
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // Cache: pages and photos already seen are kept and reused. With no
        // internet the saved copy is shown instead of an error.
        // Saved photos/files are reused even when Google marks them as
        // "expired"; only what is missing is downloaded. Pull-down refresh
        // switches to fresh downloads for that one reload.
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
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
                        touchAtTop = false; // the page confirms "at top" again for this touch
                        downX = ev.getX();
                        downTime = ev.getEventTime();
                        edgeStart = downX < 28f * density;
                        drawerDone = false;
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        // Side menu: swipe in from the left edge to open it,
                        // swipe left to close it.
                        if (!drawerDone && ev.getPointerCount() == 1) {
                            String cur = webView.getUrl();
                            boolean onPhoto = cur != null && cur.contains("/photo/");
                            float dx = ev.getX() - downX;
                            float ady = Math.abs(ev.getRawY() - pullStartY);
                            if (!onPhoto && edgeStart && dx > 48f * density && ady < dx * 0.6f) {
                                drawerDone = true;
                                pullTracking = false;
                                webView.evaluateJavascript(
                                        "window.__kpDrawer&&window.__kpDrawer('open')", null);
                            } else if (!onPhoto && dx < -70f * density && ady < -dx * 0.6f) {
                                drawerDone = true;
                                pullTracking = false;
                                webView.evaluateJavascript(
                                        "window.__kpDrawer&&window.__kpDrawer('close')", null);
                            }
                        }
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
                                    && ev.getEventTime() - downTime > 100
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
                    toast("Disabled page: " + shortPath(url));
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
                    toast("Disabled page: " + shortPath(url));
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

            // Diagnostic: while on the video-creation pages, show the page's
            // own error messages (at most one every 2 seconds).
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                try {
                    String u = webView.getUrl();
                    if (u != null && (u.contains("/movies") || u.contains("/animation") || u.contains("/collage"))
                            && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        long now = System.currentTimeMillis();
                        if (now - lastConsoleToast > 2000) {
                            lastConsoleToast = now;
                            String msg = m.message();
                            if (msg.length() > 90) msg = msg.substring(0, 90);
                            toast("Page error: " + msg);
                        }
                    }
                } catch (Exception ignored) {
                }
                return super.onConsoleMessage(m);
            }

            // Buttons that open a "new tab": load the page here instead
            // (still only if it is an allowed Google Photos page).
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                final WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    private boolean handled = false;

                    private void open(String u) {
                        if (handled || u == null || u.equals("about:blank")) return;
                        handled = true;
                        if (!isAllowed(u)) {
                            toast("Only Google Photos can be opened in this app.");
                        } else if (isBlockedPage(u)) {
                            toast("Disabled page: " + shortPath(u));
                        } else {
                            webView.loadUrl(u);
                        }
                        temp.post(new Runnable() {
                            @Override
                            public void run() {
                                temp.destroy();
                            }
                        });
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        open(r.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String u, Bitmap f) {
                        open(u);
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
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
                if (resultCode == RESULT_OK) {
                    if (results == null || results.length == 0) {
                        toast("The picker returned no photo. Try choosing from Gallery or Files.");
                    } else {
                        toast("Sending " + results.length + " file(s) to Google Photos...");
                    }
                }
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
    // page it skips past photos and pages you opened from this one (so
    // backing out of an album or Faces does not reopen what you just left)
    // and leaves the app when there is no earlier page to return to.
    private boolean isHomePage(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        if (host == null || !host.equalsIgnoreCase("photos.google.com")) return false;
        String path = u.getPath() == null ? "" : u.getPath();
        return path.isEmpty() || path.equals("/") || path.matches("/u/\\d+/?");
    }

    // Returns true when the back press was handled (it never leaves the app
    // from the Google Photos pages, and never returns to the sign-in page).
    private boolean goBackSmart() {
        String current = webView.getUrl();
        Uri cu = current == null ? null : Uri.parse(current);
        String chost = cu == null ? null : cu.getHost();
        if (chost == null || !chost.equalsIgnoreCase("photos.google.com")) {
            return false; // sign-in or other page: normal back / leave
        }
        WebBackForwardList list = webView.copyBackForwardList();
        int idx = list.getCurrentIndex();
        boolean onPhoto = current.contains("/photo/");
        // If you already visited this same page earlier (for example the
        // Faces list, then a face, then the in-page back arrow), everything
        // opened after that first visit is skipped.
        int start = idx;
        if (!onPhoto) {
            for (int j = 0; j < idx; j++) {
                WebHistoryItem it = list.getItemAtIndex(j);
                if (it != null && current.equals(it.getUrl())) {
                    start = j;
                    break;
                }
            }
        }
        for (int i = start - 1; i >= 0; i--) {
            WebHistoryItem item = list.getItemAtIndex(i);
            String u = item == null ? null : item.getUrl();
            if (u == null || !u.contains("photos.google.com")) continue;
            if (!onPhoto && u.contains("/photo/")) continue;
            if (u.equals(current)) continue;
            webView.goBackOrForward(i - idx);
            return true;
        }
        // Nothing earlier inside Google Photos.
        if (!isHomePage(current)) {
            webView.loadUrl(HOME_URL); // go to the main page
        }
        return true; // already on the main page: stay in the app
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            leaveFullScreenVideo();
        } else if (!goBackSmart()) {
            super.onBackPressed(); // sign-in page: leave the app
        }
    }
}
