package com.speedshield.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The YouTube website with an ad hiding and skipping script added. */
public class YouTubeActivity extends Activity {
    private FrameLayout root;
    private WebView web;
    private String script = "";
    private View fullScreen;
    private WebChromeClient.CustomViewCallback fullScreenDone;
    private WebChromeClient chrome;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF000000);
        script = readScript();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme())) return true; // do not jump into other apps
                String host = url.getHost();
                if (host != null && (host.equals("youtube.com") || host.endsWith(".youtube.com")
                        || host.equals("youtu.be") || host.endsWith(".google.com"))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException e) {
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (progress > 30) inject();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback done) {
                if (fullScreen != null) {
                    done.onCustomViewHidden();
                    return;
                }
                fullScreen = view;
                fullScreenDone = done;
                web.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override
            public void onHideCustomView() {
                if (fullScreen == null) return;
                root.removeView(fullScreen);
                fullScreen = null;
                web.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                fullScreenDone.onCustomViewHidden();
            }
        };
        web.setWebChromeClient(chrome);

        root.addView(web);
        setContentView(root);
        web.loadUrl("https://m.youtube.com/");
    }

    private void inject() {
        String url = web.getUrl();
        if (url == null) return;
        String host = Uri.parse(url).getHost();
        if (host != null && host.endsWith("youtube.com")) web.evaluateJavascript(script, null);
    }

    private String readScript() {
        try (InputStream in = getAssets().open("youtube.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("youtube.js is missing", e);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullScreen != null) chrome.onHideCustomView();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
