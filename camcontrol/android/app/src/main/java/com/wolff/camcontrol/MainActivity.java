package com.wolff.camcontrol;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.view.Window;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "http://10.0.0.1:8080/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        WebView web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // Show setup page if gateway not reachable
                view.loadData(
                    "<html><body style='background:#0f172a;color:#e2e8f0;font-family:sans-serif;padding:40px;text-align:center'>" +
                    "<h2>CamControl</h2><p>Cannot reach gateway.<br>Enter your gateway address below:</p>" +
                    "<input id='u' value='" + DEFAULT_URL + "' style='width:80%;padding:8px;font-size:16px;border-radius:6px'><br><br>" +
                    "<button onclick='location.href=document.getElementById(\"u\").value' style='padding:12px 24px;font-size:16px;border-radius:6px;background:#3b82f6;color:#fff;border:none'>Connect</button>" +
                    "</body></html>",
                    "text/html", "utf-8");
            }
        });

        // Read saved gateway URL or use default
        String url = getPreferences(MODE_PRIVATE).getString("gateway_url", DEFAULT_URL);
        web.loadUrl(url);
    }
}
