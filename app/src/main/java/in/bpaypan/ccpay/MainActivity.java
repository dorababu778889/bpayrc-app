package in.bpaypan.ccpay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {

    public static final String PORTAL_URL = "https://ccpay.bpaypan.co.in";
    private static final int FILE_CHOOSER_RESULT_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_CODE = 1002;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#1e40af"));
        }

        swipeRefreshLayout = new SwipeRefreshLayout(this);
        webView = new WebView(this);
        swipeRefreshLayout.addView(webView);
        setContentView(swipeRefreshLayout);

        requestNotificationPermissionIfNeeded();
        startBpayBackgroundService();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " BpayRCApp/2.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(new BpayJsBridge(), "AndroidBridge");

        swipeRefreshLayout.setColorSchemeColors(Color.parseColor("#2563eb"));
        swipeRefreshLayout.setOnRefreshListener(() -> webView.reload());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return handleCustomUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleCustomUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefreshLayout.setRefreshing(false);

                // Sync user mobile to Native Background Service & route notifications to Native Android NotificationManager
                String jsHook = "(function() {" +
                        "  try {" +
                        "    if ('serviceWorker' in navigator) { navigator.serviceWorker.getRegistrations().then(function(regs) { regs.forEach(function(r) { r.unregister(); }); }); }
    var apkWrap = document.getElementById('apkDownloadWrap');" +
                        "    if (apkWrap) apkWrap.style.display = 'none';" +
                        "    var mob = sessionStorage.getItem('userMobile') || localStorage.getItem('bpay_remembered_mobile') || '';" +
                        "    if (mob && window.AndroidBridge) window.AndroidBridge.syncMobile(mob);" +
                        "    window.showUserPushNotification = function(title, body, icon) {" +
                        "      if (window.AndroidBridge) window.AndroidBridge.triggerNotification(title || 'BPAY RC', body || '');" +
                        "    };" +
                        "    window.showRichBpayNotification = function(title, body, icon) {" +
                        "      if (window.AndroidBridge) window.AndroidBridge.triggerNotification(title || 'BPAY RC Admin', body || '');" +
                        "    };" +
                        "    var origDownloadQR = window.downloadQR;" +
                        "    if (origDownloadQR && !window._bpayNativeQrHooked) {" +
                        "      window._bpayNativeQrHooked = true;" +
                        "      document.addEventListener('click', function(e) {" +
                        "        var a = e.target.closest('a[download]');" +
                        "        if (a && a.href && a.href.startsWith('data:image/')) {" +
                        "          e.preventDefault();" +
                        "          window.AndroidBridge.saveBase64Image(a.href, a.getAttribute('download') || 'Payment_QR.png');" +
                        "        }" +
                        "      }, true);" +
                        "    }" +
                        "  } catch(e) {}" +
                        "})();";
                webView.evaluateJavascript(jsHook, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url.startsWith("data:image/")) {
                        saveDataUrlImage(url, "Payment_QR.png");
                        return;
                    }
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    request.addRequestHeader("cookie", cookies);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setDescription("Downloading file...");
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(fileName);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        Toast.makeText(getApplicationContext(), "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                }
            }
        });

        webView.loadUrl(PORTAL_URL);
    }

    private boolean handleCustomUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (url.contains("wa.me/") || url.contains("api.whatsapp.com") || url.contains("t.me/")) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "App not installed for this link.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void saveDataUrlImage(String dataUrl, String fileName) {
        try {
            String base64Data = dataUrl.substring(dataUrl.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(decodedBytes);
            fos.flush();
            fos.close();
            Toast.makeText(this, "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show();
            BpayNotificationService.fireNativeNotification(this, "QR Code Downloaded", "Saved to Downloads/" + fileName);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save QR image.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void startBpayBackgroundService() {
        try {
            Intent serviceIntent = new Intent(this, BpayNotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {}
    }

    public class BpayJsBridge {
        @JavascriptInterface
        public void syncMobile(String mobile) {
            if (mobile != null && !mobile.trim().isEmpty()) {
                SharedPreferences prefs = getSharedPreferences("bpay_prefs", Context.MODE_PRIVATE);
                prefs.edit().putString("user_mobile", mobile.trim()).apply();
            }
        }

        @JavascriptInterface
        public void triggerNotification(String title, String body) {
            BpayNotificationService.fireNativeNotification(MainActivity.this, title, body);
        }

        @JavascriptInterface
        public void saveBase64Image(String dataUrl, String fileName) {
            runOnUiThread(() -> saveDataUrlImage(dataUrl, fileName));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
