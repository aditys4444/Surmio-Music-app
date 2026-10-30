package com.surmio.music;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Unlock maximum display refresh rate (90Hz, 120Hz, 144Hz) for ultra-smooth UI scrolling
        unlockMaxRefreshRate();

        // Keep audio streaming and media playback active in WebView
        if (bridge != null && bridge.getWebView() != null) {
            WebView webView = bridge.getWebView();
            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);

            WebSettings settings = webView.getSettings();
            webView.clearCache(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(false);
            }
            try {
                settings.setOffscreenPreRaster(true);
            } catch (Exception ignored) {}

            // Guarantee continuous background audio in YouTube iframes by overriding visibility detection across all frames
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    String bgAudioScript = 
                        "try {" +
                        "  Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });" +
                        "  Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });" +
                        "  window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);" +
                        "  document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);" +
                        "} catch (e) {}";
                    java.util.Set<String> origins = new java.util.HashSet<>();
                    origins.add("*");
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, bgAudioScript, origins);
                } catch (Exception ignored) {}
            }

            // Register native bridge for real-time notification shade updates
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void updatePlaybackState(String title, String artist, String artworkUrl, boolean isPlaying, long durationMs, long positionMs) {
                    try {
                        Intent intent = new Intent(MainActivity.this, BackgroundAudioService.class);
                        intent.setAction(BackgroundAudioService.ACTION_UPDATE_TRACK);
                        intent.putExtra("title", title);
                        intent.putExtra("artist", artist);
                        intent.putExtra("artworkUrl", artworkUrl);
                        intent.putExtra("isPlaying", isPlaying);
                        intent.putExtra("durationMs", durationMs);
                        intent.putExtra("positionMs", positionMs);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                    } catch (Exception ignored) {}
                }
            }, "AndroidAudioBridge");
        }

        // Connect notification transport controls (Prev, Play/Pause, Next, Seek) to WebView JS
        BackgroundAudioService.setControlListener(new BackgroundAudioService.MediaControlListener() {
            @Override
            public void onPlay() {
                runOnUiThread(() -> {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript("if (window.playMedia) { window.playMedia(); }", null);
                    }
                });
            }

            @Override
            public void onPause() {
                runOnUiThread(() -> {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript("if (window.pauseMedia) { window.pauseMedia(); }", null);
                    }
                });
            }

            @Override
            public void onNext() {
                runOnUiThread(() -> {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript("if (window.playNextTrack) { window.playNextTrack(); }", null);
                    }
                });
            }

            @Override
            public void onPrev() {
                runOnUiThread(() -> {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript("if (window.playPrevTrack) { window.playPrevTrack(); }", null);
                    }
                });
            }

            @Override
            public void onSeekTo(long posMs) {
                long sec = posMs / 1000;
                runOnUiThread(() -> {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript("if (window.seekToTime) { window.seekToTime(" + sec + "); }", null);
                    }
                });
            }
        });

        // Start Foreground Service to guarantee Android never halts background audio
        startAudioService();
    }

    private void startAudioService() {
        try {
            Intent serviceIntent = new Intent(this, BackgroundAudioService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void unlockMaxRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Window window = getWindow();
                if (window != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    Display display = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        display = getDisplay();
                    } else {
                        display = window.getWindowManager().getDefaultDisplay();
                    }
                    if (display != null) {
                        Display.Mode[] modes = display.getSupportedModes();
                        if (modes != null && modes.length > 0) {
                            Display.Mode bestMode = modes[0];
                            for (Display.Mode mode : modes) {
                                if (mode.getRefreshRate() > bestMode.getRefreshRate()) {
                                    bestMode = mode;
                                }
                            }
                            params.preferredDisplayModeId = bestMode.getModeId();
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                params.preferredRefreshRate = bestMode.getRefreshRate();
                            }
                            window.setAttributes(params);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Keep WebView audio thread and JavaScript timers running when user exits app to Home or switches apps
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        // Prevent Android OS from pausing background audio buffering and timers
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
    }

    @Override
    public void onBackPressed() {
        // Intercept back button to move task to background rather than tearing down the WebView audio pipeline
        // This guarantees continuous, smooth background music playback just like Spotify / Apple Music
        try {
            if (bridge != null && bridge.getWebView() != null) {
                // If a drawer or CineVault is open, let JS close it, otherwise move task to back
                bridge.getWebView().evaluateJavascript(
                    "(function() { " +
                    "  var cv = document.getElementById('cineVaultOverlay'); " +
                    "  if (cv && cv.classList.contains('active')) { " +
                    "    if (window.closeCineVault) { window.closeCineVault(); return 'handled'; } " +
                    "  } " +
                    "  return 'none'; " +
                    "})()",
                    value -> {
                        if (value == null || !value.contains("handled")) {
                            moveTaskToBack(true);
                        }
                    }
                );
                return;
            }
        } catch (Exception ignored) {}
        moveTaskToBack(true);
    }

    @Override
    public void onDestroy() {
        // Only stop service if whole process is genuinely finishing and not playing
        super.onDestroy();
    }
}
