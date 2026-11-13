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
        "photos.app.goo.gl",
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
        // Touches on the right-hand edge (the date scrubber) never count as "at top".
        + "  var tx=(e.touches&&e.touches[0])?e.touches[0].clientX:0;"
        + "  if(tx>window.innerWidth-72)top=false;"
        // Photos scrolls inside its own container: check the container in the middle of the screen too.
        + "  var cc=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);"
        + "  while(cc&&cc!==document){if(cc.scrollTop>0){top=false;break;}cc=cc.parentElement;}"
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

    // Full screen for a single photo: tap the middle to hide / show the bars.
    private static final String FS_JS =
          "(function(){if(window.__kpFS)return;"
        + "var st=document.createElement('style');st.textContent='[data-kpfs]{visibility:hidden!important}';"
        + "document.head.appendChild(st);"
        + "function clr(){document.querySelectorAll('[data-kpfs]').forEach(function(e){e.removeAttribute('data-kpfs');});}"
        + "function app(){var W=innerWidth,H=innerHeight;"
        + " document.querySelectorAll('body *').forEach(function(e){"
        + "  var cs=getComputedStyle(e);"
        + "  if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky')return;"
        + "  var r=e.getBoundingClientRect();if(r.width<1||r.height<1)return;"
        + "  var top=r.top<=6&&r.height<=170&&r.width>=W*0.5;"
        + "  var bot=r.bottom>=H-6&&r.height<=220&&r.width>=W*0.5;"
        + "  var side=r.width<=110&&r.height<=140&&(r.left<=12||r.right>=W-12)&&r.top>H*0.25&&r.bottom<H*0.75;"
        + "  if((top||bot||side)&&!e.querySelector('video'))e.setAttribute('data-kpfs','1');"
        + " });}"
        + "window.__kpFS=function(xf,yf,on){"
        + " var t=document.elementFromPoint(innerWidth*xf,innerHeight*yf);"
        + " if(t&&t.closest&&t.closest('video,input,button,[role=slider]'))return 'skip';"
        + " if(on)app();else clr();return 'ok';};"
        + "window.__kpFSApply=function(){app();};"
        + "window.__kpFSClear=clr;"
        + "})();";

    private boolean immersive = false;
    private boolean swipeMulti = false;
    private float baseScale = 0f;
    private long lastTapTime = 0;
    private float tapX, tapY;
    private long tapDownTime;
    private boolean tapCandidate = false;
    private Runnable pendingTap;

    @SuppressWarnings("deprecation")
    private void setPhotoImmersive(boolean on) {
        immersive = on;
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void exitPhotoFullScreen() {
        if (!immersive) return;
        setPhotoImmersive(false);
        webView.evaluateJavascript("window.__kpFSClear&&window.__kpFSClear()", null);
    }

    // A quick tap in the middle of an open photo: toggle full screen.
    private void photoTap(final float xf, final float yf) {
        final boolean turnOn = !immersive;
        webView.evaluateJavascript("window.__kpFS?window.__kpFS(" + xf + "," + yf + "," + turnOn + "):'none'",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (v != null && v.contains("ok")) setPhotoImmersive(turnOn);
                    }
                });
    }

    // A side scrubber for albums and other long pages (the normal photo
    // timeline already has its own): drag the handle on the right edge to
    // jump through the page. Its size shows how long the page is.
    private static final String SCRUB_JS =
        "(function(){"
        + "if(window.__kpScrub)return;window.__kpScrub=true;"
        + "var box=null,thumb=null,lab=null,sc=null,hideT=null,hideD=null,drag=false,vis=false,last=-1;"
        + "function pageOk(){var p=location.pathname;"
        + "  if(/\\/photo\\//.test(p))return false;"
        + "  if(p==='/'||/^\\/u\\/\\d+\\/?$/.test(p))return false;"
        + "  return true;}"
        + "function build(){"
        + "  if(box)return;"
        + "  box=document.createElement('div');"
        + "  box.style.cssText='position:fixed;right:0;top:72px;bottom:24px;width:44px;z-index:2147483000;display:none;pointer-events:none;opacity:0;transform:translateX(60px);transition:transform .22s ease,opacity .22s ease;';"
        + "  thumb=document.createElement('div');"
        + "  thumb.style.cssText='position:absolute;right:2px;width:40px;height:56px;border-radius:28px 0 0 28px;background:rgba(48,52,56,0.94);color:#e8eaed;display:flex;align-items:center;justify-content:center;font:16px sans-serif;pointer-events:auto;touch-action:none;box-shadow:0 1px 4px rgba(0,0,0,0.5);';"
        + "  thumb.textContent='\\u2195';"
        + "  lab=document.createElement('div');"
        + "  lab.style.cssText='position:absolute;right:48px;height:40px;padding:0 18px;border-radius:20px;background:rgba(48,52,56,0.94);color:#e8eaed;font:600 15px sans-serif;display:none;align-items:center;justify-content:center;text-align:center;white-space:nowrap;box-sizing:border-box;';"
        + "  box.appendChild(lab);box.appendChild(thumb);"
        + "  document.documentElement.appendChild(box);"
        + "  function move(y){"
        + "    if(!sc)return;"
        + "    var r=box.getBoundingClientRect(),th=56;"
        + "    var f=(y-r.top-th/2)/Math.max(1,(r.height-th));"
        + "    f=Math.max(0,Math.min(1,f));"
        + "    sc.scrollTop=f*(sc.scrollHeight-sc.clientHeight);"
        + "    update();"
        + "  }"
        + "  thumb.addEventListener('touchstart',function(e){drag=true;show();lab.style.display='flex';clearTimeout(hideT);e.preventDefault();e.stopPropagation();},{passive:false});"
        + "  thumb.addEventListener('touchmove',function(e){if(!drag)return;move(e.touches[0].clientY);e.preventDefault();e.stopPropagation();},{passive:false});"
        + "  function end(){drag=false;lab.style.display='none';later();}"
        + "  thumb.addEventListener('touchend',end,{passive:true});"
        + "  thumb.addEventListener('touchcancel',end,{passive:true});"
        + "}"
        + "function update(){"
        + "  if(!sc||!box)return;"
        + "  var range=sc.scrollHeight-sc.clientHeight;"
        + "  var f=range>0?sc.scrollTop/range:0;"
        + "  f=Math.max(0,Math.min(1,f));"
        + "  var th=56,bh=box.clientHeight;"
        + "  thumb.style.top=(f*Math.max(0,bh-th))+'px';"
        + "  lab.style.top=(f*Math.max(0,bh-th)+8)+'px';"
        + "  lab.textContent=Math.round(f*100)+'%  \\u00b7  '+Math.max(1,Math.round(sc.scrollHeight/sc.clientHeight))+' screens';"
        + "}"
        + "function show(){clearTimeout(hideD);if(!vis){box.style.display='block';void box.offsetWidth;box.style.transform='translateX(0)';box.style.opacity='1';vis=true;}}"
        + "function hide(){if(!box||!vis)return;vis=false;box.style.transform='translateX(60px)';box.style.opacity='0';hideD=setTimeout(function(){if(!vis)box.style.display='none';},260);}"
        + "function later(){clearTimeout(hideT);hideT=setTimeout(function(){if(!drag)hide();},1200);}"
        + "document.addEventListener('scroll',function(e){"
        + "  var t=e.target;"
        + "  var el=(t===document)?(document.scrollingElement||document.documentElement):t;"
        + "  if(!el||el.nodeType!==1)return;"
        + "  if(el.clientHeight<window.innerHeight*0.5)return;"
        + "  if(el.scrollHeight-el.clientHeight<window.innerHeight*0.5)return;"
        + "  if(!pageOk()){hide();return;}"
        + "  build();sc=el;var st=el.scrollTop;"
        + "  if(!drag&&Math.abs(st-last)<2)return;"
        + "  last=st;show();update();"
        + "  if(!drag)later();"
        + "},true);"
        + "})();";

    private void injectBannerHider(WebView view) {
        view.evaluateJavascript(SCRUB_JS, null);
        view.evaluateJavascript(FS_JS, null);
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
                    if (ev.getPointerCount() > 1) swipeMulti = true;
                    if (action == MotionEvent.ACTION_DOWN) {
                        swipeMulti = false;
                        pullStartY = ev.getRawY();
                        pullTracking = !refreshing;
                        // Dragging the scrubber on the right edge is never a refresh.
                        if (ev.getX() > webView.getWidth() - 72f * density) pullTracking = false;
                        pulling = false;
                        touchAtTop = false; // the page confirms "at top" again for this touch
                        downX = ev.getX();
                        downTime = ev.getEventTime();
                        edgeStart = downX < 28f * density;
                        drawerDone = false;
                        tapCandidate = true;
                        tapX = ev.getX();
                        tapY = ev.getY();
                        tapDownTime = ev.getEventTime();
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (ev.getPointerCount() > 1
                                || Math.abs(ev.getX() - tapX) > slop
                                || Math.abs(ev.getY() - tapY) > slop) {
                            tapCandidate = false;
                        }
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
                                    && !(webView.getUrl() != null && webView.getUrl().contains("/photo/"))
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
                        String curUrl = webView.getUrl();
                        if (action == MotionEvent.ACTION_UP && tapCandidate
                                && curUrl != null && curUrl.contains("/photo/")
                                && ev.getEventTime() - tapDownTime < 300) {
                            final float xf = tapX / Math.max(1, webView.getWidth());
                            final float yf = tapY / Math.max(1, webView.getHeight());
                            if (xf > 0.2f && xf < 0.8f && yf > 0.15f && yf < 0.85f) {
                                long now = ev.getEventTime();
                                if (pendingTap != null) {
                                    webView.removeCallbacks(pendingTap); // double tap = zoom, not full screen
                                    pendingTap = null;
                                    lastTapTime = 0;
                                } else {
                                    lastTapTime = now;
                                    pendingTap = new Runnable() {
                                        @Override
                                        public void run() {
                                            pendingTap = null;
                                            photoTap(xf, yf);
                                        }
                                    };
                                    webView.postDelayed(pendingTap, 320);
                                }
                            }
                        }
                        // Swipe down on an open photo / video: close it, like a gallery app.
                        if (action == MotionEvent.ACTION_UP && !swipeMulti && !pulling
                                && curUrl != null && curUrl.contains("/photo/")) {
                            float sdy = ev.getRawY() - pullStartY;
                            float sdx = ev.getX() - downX;
                            boolean zoomed = baseScale > 0 && webView.getScale() > baseScale * 1.08f;
                            if (!zoomed && sdy > 110f * density && Math.abs(sdx) < sdy * 0.6f
                                    && ev.getEventTime() - downTime < 900) {
                                if (pendingTap != null) {
                                    webView.removeCallbacks(pendingTap);
                                    pendingTap = null;
                                }
                                exitPhotoFullScreen();
                                if (!goBackSmart()) goHome(webView);
                            }
                        }
                        tapCandidate = false;
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
                if (!url.contains("/photo/")) {
                    exitPhotoFullScreen();
                } else if (immersive) {
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("window.__kpFSApply&&window.__kpFSApply()", null);
                        }
                    }, 500);
                }
                if (!isAllowed(url)) {
                    toast("Only Google Photos can be opened in this app.");
                    goHome(view);
                } else if (isBlockedPage(url)) {
                    toast("Disabled page: " + shortPath(url));
                    goHome(view);
                }
            }

            @Override
            public void onScaleChanged(WebView view, float oldScale, float newScale) {
                if (baseScale <= 0) baseScale = Math.min(oldScale, newScale);
                else if (newScale < baseScale) baseScale = newScale;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                baseScale = view.getScale();
                view.setVisibility(View.VISIBLE);
                finishRefresh();
                if (url != null && url.contains("photos.google.com")) {
                    injectBannerHider(view);
                }
                if (shareJsPending && pendingShare != null && url != null && isHomePage(url)) {
                    shareJsPending = false;
                    view.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            runShareUpload();
                        }
                    }, 1500);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // "Add photos" / any upload button: open the phone's photo picker.
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                Uri[] shared = takePendingShare();
                if (shared != null) {
                    callback.onReceiveValue(shared);
                    view.evaluateJavascript("window.__kpUpStop=1", null);
                    toast("Sending " + shared.length + " file(s) to Google Photos...");
                    return true;
                }
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("*/*");
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(pick, FILE_CHOOSER_REQUEST);
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

        if (ACTION_OPEN_CLIPBOARD.equals(getIntent().getAction())) pendingClipboard = true;
        handleSharedMedia(getIntent());
        String shared = sharedLinkFrom(getIntent());
        webView.loadUrl(shared != null ? shared : START_URL);
    }

    // A Google Photos album link that was tapped or shared to this app
    // (photos.app.goo.gl/... or photos.google.com/share/...), or null.
    private String sharedLinkFrom(Intent intent) {
        if (intent == null) return null;
        String raw = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            raw = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) raw = t.toString();
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (t != null) raw = t.toString();
        }
        return albumLinkIn(raw);
    }

    // Finds a Google Photos album link inside some text, or null.
    private String albumLinkIn(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https://(photos\\.app\\.goo\\.gl/[^\\s]+|photos\\.google\\.com/[^\\s]*share/[^\\s]+)")
                .matcher(raw);
        if (!m.find()) return null;
        String url = m.group();
        return (isAllowed(url) && !isBlockedPage(url)) ? url : null;
    }

    // "Paste album link" (long-press the app icon): the clipboard can only be
    // read once the app is in front, so this waits for the window focus.
    private static final String ACTION_OPEN_CLIPBOARD = "com.example.gphotosviewer.OPEN_CLIPBOARD";
    private boolean pendingClipboard = false;

    // ---- Share photos/videos TO this app: they are uploaded to Google Photos ----
    private Uri[] pendingShare = null;
    private long pendingShareTime = 0L;
    private boolean shareJsPending = false;
    private static final long SHARE_VALID_MS = 15 * 60 * 1000L;

    // Clicks "Upload" then "Computer" on the Google Photos page. The page then
    // asks for files, and onShowFileChooser hands over the shared ones.
    private static final String SHARE_JS =
        "(function(){"
        + "window.__kpUpStop=0;var tries=0,lastPlus=0;"
        + "function txt(e){return ((e.getAttribute('aria-label')||'')+' '+(e.innerText||'')).trim().toLowerCase();}"
        + "function vis(e){return e.getClientRects().length>0;}"
        + "function find(re,sel,bad){var l=document.querySelectorAll(sel);"
        + "  for(var i=0;i<l.length;i++){var t=txt(l[i]);"
        + "    if(re.test(t)&&!(bad&&bad.test(t))&&vis(l[i]))return l[i];}return null;}"
        // Waits patiently (about 100 seconds) so slow connections can finish loading.
        + "function step(){"
        + "  if(window.__kpUpStop)return;"
        + "  tries++;"
        + "  var item=find(/^(upload|from computer|computer|this device|device)\\b/,'[role=menuitem],[role=option],li,[role=listitem]');"
        + "  if(item){item.click();return;}"
        + "  var now=Date.now();"
        + "  if(now-lastPlus>3500){"
        + "    var plus=find(/^(create|add|new|upload|plus|\\+)/,'button,[role=button],[aria-label]',/album|share|person|people|account|favorite|search/);"
        + "    if(plus){plus.click();lastPlus=now;}"
        + "  }"
        + "  if(tries<200)setTimeout(step,500);"
        + "}"
        + "step();"
        + "})();";

    // Picks the photos and videos out of a share intent. Returns true when
    // this was a photo/video share (even if nothing usable was in it).
    private boolean handleSharedMedia(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        boolean one = Intent.ACTION_SEND.equals(action);
        boolean many = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (!one && !many) return false;
        String type = intent.getType();
        if (type != null && type.startsWith("text/")) return false;

        java.util.ArrayList<Uri> found = new java.util.ArrayList<>();
        try {
            if (one) {
                Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (o instanceof Uri) found.add((Uri) o);
            } else {
                java.util.ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) found.addAll(l);
            }
            if (found.isEmpty() && intent.getClipData() != null) {
                for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                    Uri u = intent.getClipData().getItemAt(i).getUri();
                    if (u != null) found.add(u);
                }
            }
        } catch (Exception ignored) {
        }
        java.util.ArrayList<Uri> media = new java.util.ArrayList<>();
        for (Uri u : found) {
            String t = null;
            try {
                t = getContentResolver().getType(u);
            } catch (Exception ignored) {
            }
            if (t == null) t = type;
            if (t != null && (t.startsWith("image/") || t.startsWith("video/")
                    || t.equals("*/*"))) {
                media.add(u);
            }
        }
        if (media.isEmpty()) {
            toast("Nothing to upload: share photos or videos.");
            return true;
        }
        pendingShare = media.toArray(new Uri[0]);
        pendingShareTime = System.currentTimeMillis();
        shareJsPending = true;
        toast("Getting ready to upload " + media.size() + " file(s)...");
        return true;
    }

    private Uri[] takePendingShare() {
        Uri[] u = pendingShare;
        boolean fresh = u != null && System.currentTimeMillis() - pendingShareTime < SHARE_VALID_MS;
        pendingShare = null;
        shareJsPending = false;
        return fresh ? u : null;
    }

    // Starts the upload on the Google Photos main page.
    private void runShareUpload() {
        if (pendingShare == null || webView == null) return;
        webView.evaluateJavascript(SHARE_JS, null);
        final long stamp = pendingShareTime;
        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingShare != null && pendingShareTime == stamp) {
                    toast("Tap +, then Upload. Your " + pendingShare.length
                            + " shared file(s) will be sent.");
                }
            }
        }, 45000);
    }

    private void startShareOnHome() {
        String cur = webView == null ? null : webView.getUrl();
        if (cur != null && isHomePage(cur)) {
            shareJsPending = false;
            runShareUpload();
        } else if (webView != null) {
            webView.loadUrl(HOME_URL); // onPageFinished starts the upload
        }
    }

    private android.app.AlertDialog pasteDialog;

    private String clipboardLink() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    CharSequence t = clip.getItemAt(i).coerceToText(this);
                    String link = t == null ? null : albumLinkIn(t.toString());
                    if (link != null) return link;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Shows a small box to paste an album link into. If a Google Photos link
    // was just copied, it is already filled in.
    private void openClipboardLink() {
        pendingClipboard = false;
        if (pasteDialog != null && pasteDialog.isShowing()) return;
        final float d = getResources().getDisplayMetrics().density;
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setHint("Paste a Google Photos album link");
        box.setSingleLine(true);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        String link = clipboardLink();
        if (link != null) {
            box.setText(link);
            box.selectAll();
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        wrap.addView(box);

        pasteDialog = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Open album link")
                .setView(wrap)
                .setPositiveButton("Open", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dlg, int which) {
                        String l = albumLinkIn(box.getText().toString());
                        if (l != null) webView.loadUrl(l);
                        else toast("That is not a Google Photos album link.");
                    }
                })
                .setNeutralButton("Paste", null)
                .setNegativeButton("Cancel", null)
                .create();
        pasteDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        pasteDialog.show();
        pasteDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String l = clipboardLink();
                        if (l != null) {
                            box.setText(l);
                            box.setSelection(l.length());
                        } else {
                            toast("No Google Photos link copied yet.");
                        }
                    }
                });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingClipboard && webView != null) openClipboardLink();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_OPEN_CLIPBOARD.equals(intent.getAction())) {
            pendingClipboard = true;
            if (hasWindowFocus() && webView != null) openClipboardLink();
            return;
        }
        if (handleSharedMedia(intent)) {
            if (pendingShare != null) startShareOnHome();
            return;
        }
        String shared = sharedLinkFrom(intent);
        if (shared != null && webView != null) webView.loadUrl(shared);
    }

    // Reads the chosen photos from every place a picker may put them.
    private Uri[] readPickedUris(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        java.util.ArrayList<Uri> list = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) list.add(u);
            }
        }
        if (list.isEmpty() && data.getData() != null) list.add(data.getData());
        if (list.isEmpty()) {
            try {
                Object o = data.getParcelableExtra(Intent.EXTRA_STREAM);
                if (o instanceof Uri) list.add((Uri) o);
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            try {
                java.util.ArrayList<Uri> l = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (l != null) list.addAll(l);
            } catch (Exception ignored) {
            }
        }
        return list.isEmpty() ? null : list.toArray(new Uri[0]);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                // Make sure the picker is closed and this app is in front again.
                try {
                    Intent back = new Intent(this, MainActivity.class);
                    back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(back);
                } catch (Exception ignored) {
                }
                Uri[] results = readPickedUris(resultCode, data);
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
                if (resultCode == RESULT_OK) {
                    if (results == null || results.length == 0) {
                        String d = (data == null) ? "no data"
                                : ("data=" + data.getData() + " clip="
                                + (data.getClipData() == null ? "none" : data.getClipData().getItemCount())
                                + " extras=" + (data.getExtras() == null ? "none" : data.getExtras().keySet()));
                        toast("The picker returned no photo (" + d + ")");
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
        } else if (immersive) {
            exitPhotoFullScreen();
        } else if (!goBackSmart()) {
            super.onBackPressed(); // sign-in page: leave the app
        }
    }
}
