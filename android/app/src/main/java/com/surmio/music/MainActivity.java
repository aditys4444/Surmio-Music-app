package com.surmio.music;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        this.keepRunning = true;
        super.onCreate(savedInstanceState);

        // Unlock maximum display refresh rate (90Hz, 120Hz, 144Hz) for ultra-smooth UI scrolling
        unlockMaxRefreshRate();

        // Keep audio streaming and media playback active in WebView
        if (bridge != null && bridge.getWebView() != null) {
            WebView.setWebContentsDebuggingEnabled(true);
            WebView webView = bridge.getWebView();
            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);
            webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            try {
                webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
            } catch (Exception ignored) {}

            WebSettings settings = webView.getSettings();
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

            // Register native bridge for robust hardware-accelerated playback
            webView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void playTrack(String title, String artist, String artworkUrl, String audioSrc, long durationMs) {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.playTrack(title, artist, artworkUrl, audioSrc, durationMs);
                    } else {
                        Intent intent = new Intent(MainActivity.this, BackgroundAudioService.class);
                        intent.setAction(BackgroundAudioService.ACTION_PLAY_TRACK);
                        intent.putExtra("title", title);
                        intent.putExtra("artist", artist);
                        intent.putExtra("artworkUrl", artworkUrl);
                        intent.putExtra("audioSrc", audioSrc);
                        intent.putExtra("durationMs", durationMs);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                    }
                }

                @JavascriptInterface
                public void prepareTrack(String title, String artist, String artworkUrl, String audioSrc, long durationMs) {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.prepareTrackMetadata(title, artist, artworkUrl, audioSrc, durationMs);
                    }
                }

                @JavascriptInterface
                public void pause() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.pauseAudio();
                    }
                }

                @JavascriptInterface
                public void resume() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.resumeAudio();
                    } else {
                        Intent intent = new Intent(MainActivity.this, BackgroundAudioService.class);
                        intent.setAction(BackgroundAudioService.ACTION_PLAY);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                    }
                }

                @JavascriptInterface
                public void seekTo(long positionMs) {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.seekToAudio(positionMs);
                    }
                }

                @JavascriptInterface
                public void setPlaybackSpeed(float speed) {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.setSpeed(speed);
                    }
                }

                @JavascriptInterface
                public long getCurrentPosition() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    return service != null ? service.getNativePosition() : 0;
                }

                @JavascriptInterface
                public long getDuration() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    return service != null ? service.getNativeDuration() : 0;
                }

                @JavascriptInterface
                public boolean isPlaying() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    return service != null && service.isNativePlaying();
                }

                @JavascriptInterface
                public void setQueue(String queueJson) {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null) {
                        service.updatePlaylistFromJson(queueJson);
                    }
                }

                @JavascriptInterface
                public String getCurrentTrackJson() {
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    return service != null ? service.getCurrentTrackJson() : "{}";
                }

                @JavascriptInterface
                public void updatePlaybackState(String title, String artist, String artworkUrl, boolean isPlaying, long durationMs, long positionMs) {
                    // Backwards compatibility with existing calls
                    BackgroundAudioService service = BackgroundAudioService.sInstance;
                    if (service != null && !service.isNativePlaying() && isPlaying) {
                        service.resumeAudio();
                    }
                }
            }, "AndroidAudioBridge");
        }

        // Connect native background service events back to WebView UI
        BackgroundAudioService.setUiSyncListener((event, isPlaying, posMs, durMs, title, artist, artUrl) -> {
            runOnUiThread(() -> {
                if (bridge != null && bridge.getWebView() != null) {
                    String script = String.format(
                            "if (window.onNativeAudioSync) { window.onNativeAudioSync('%s', %b, %d, %d, %s, %s, %s); }",
                            event,
                            isPlaying,
                            posMs,
                            durMs,
                            escapeJs(title),
                            escapeJs(artist),
                            escapeJs(artUrl)
                    );
                    bridge.getWebView().evaluateJavascript(script, null);
                }
            });
        });

        // Intercept modern gesture navigation and back button to move task to back
        // Prevents activity teardown and ensures continuous background music
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                try {
                    if (bridge != null && bridge.getWebView() != null) {
                        bridge.getWebView().evaluateJavascript(
                                "(function() { " +
                                "  var sm = document.getElementById('surmioSpeedModal'); " +
                                "  if (sm && sm.style.display === 'flex') { " +
                                "    if (window.closeSpeedModal) { window.closeSpeedModal(); return 'handled'; } " +
                                "    sm.style.display = 'none'; return 'handled'; " +
                                "  } " +
                                "  var qd = document.getElementById('cvQueueDrawer'); " +
                                "  if (qd && qd.classList.contains('open')) { " +
                                "    if (window.closeQueueDrawer) { window.closeQueueDrawer(); return 'handled'; } " +
                                "    qd.classList.remove('open'); return 'handled'; " +
                                "  } " +
                                "  var cv = document.getElementById('cineVaultOverlay'); " +
                                "  if (cv && cv.classList.contains('open')) { " +
                                "    if (window.closeCineVault) { window.closeCineVault(); return 'handled'; } " +
                                "    cv.classList.remove('open'); return 'handled'; " +
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
        });

        // Start Foreground Service to guarantee Android never halts background audio
        startAudioService();
    }

    private static String escapeJs(String str) {
        if (str == null) return "''";
        return "'" + str.replace("'", "\\'").replace("\n", " ") + "'";
    }

    private void startAudioService() {
        try {
            Intent serviceIntent = new Intent(this, BackgroundAudioService.class);
            startService(serviceIntent);
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
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
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
        // Fallback for devices without Predictive Back
        moveTaskToBack(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
