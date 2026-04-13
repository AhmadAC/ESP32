package com.example.mybasicapp;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class WebViewActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefreshWebView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_view);

        String url = getIntent().getStringExtra("URL");
        String name = getIntent().getStringExtra("NAME");

        TextView tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        if (name != null) tvToolbarTitle.setText(name);

        webView = findViewById(R.id.webView);
        swipeRefreshWebView = findViewById(R.id.swipeRefreshWebView);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        TextView btnBack = findViewById(R.id.btnBack);

        // Fix: Make the visual back button actually close the view
        btnBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // Fix: Allow pull-down to refresh the HTML
        swipeRefreshWebView.setOnRefreshListener(() -> {
            webView.reload();
        });

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        // Fix: Disable Cache so the app never gets stuck on a truncated page
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefreshWebView.setRefreshing(false); // Stop the loading spinner
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (url != null) {
            webView.loadUrl(url);
        }
    }
}