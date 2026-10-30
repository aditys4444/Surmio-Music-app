package com.surmio.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackgroundAudioService extends Service {

    public static final String CHANNEL_ID = "surmio_playback_channel";
    public static final int NOTIFICATION_ID = 4040;

    public static final String ACTION_PREV = "com.surmio.music.ACTION_PREV";
    public static final String ACTION_PLAY_PAUSE = "com.surmio.music.ACTION_PLAY_PAUSE";
    public static final String ACTION_PLAY = "com.surmio.music.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.surmio.music.ACTION_PAUSE";
    public static final String ACTION_NEXT = "com.surmio.music.ACTION_NEXT";
    public static final String ACTION_PLAY_TRACK = "com.surmio.music.ACTION_PLAY_TRACK";
    public static final String ACTION_SEEK_TO = "com.surmio.music.ACTION_SEEK_TO";

    public static volatile BackgroundAudioService sInstance = null;

    public static class TrackItem {
        public String title;
        public String artist;
        public String artworkUrl;
        public String audioSrc;
        public long durationMs;

        public TrackItem(String title, String artist, String artworkUrl, String audioSrc, long durationMs) {
            this.title = title != null ? title : "Surmio Music";
            this.artist = artist != null ? artist : "Surmio Studio";
            this.artworkUrl = artworkUrl != null ? artworkUrl : "";
            this.audioSrc = audioSrc != null ? audioSrc : "";
            this.durationMs = durationMs;
        }
    }

    public interface UiSyncListener {
        void onSyncEvent(String event, boolean isPlaying, long positionMs, long durationMs, String title, String artist, String artworkUrl);
    }

    private static UiSyncListener sUiSyncListener = null;

    public static void setUiSyncListener(UiSyncListener listener) {
        sUiSyncListener = listener;
    }

    private PowerManager.WakeLock wakeLock;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private ExecutorService artworkExecutor;

    private MediaPlayer mediaPlayer;
    private String currentTitle = "Surmio Music";
    private String currentArtist = "Continuous Studio Playback";
    private String lastArtworkUrl = null;
    private String currentAudioSrc = null;
    private Bitmap cachedArtwork = null;
    private boolean isPlaying = false;
    private long durationMs = 0;
    private long positionMs = 0;
    private float currentSpeed = 1.0f;

    private final List<TrackItem> playlist = new ArrayList<>();
    private int currentIndex = 0;

    public static BackgroundAudioService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        artworkExecutor = Executors.newSingleThreadExecutor();

        createNotificationChannel();
        initMediaSession();

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Surmio::PlaybackWakeLock");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours max
            } catch (Exception ignored) {}
        }

        // Audio service initialized; startForeground will be promoted when audio plays
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "SurmioMusicSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resumeAudio();
            }

            @Override
            public void onPause() {
                pauseAudio();
            }

            @Override
            public void onSkipToNext() {
                playNextTrackNatively();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevTrackNatively();
            }

            @Override
            public void onSeekTo(long pos) {
                seekToAudio(pos);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PREV.equals(action)) {
                playPrevTrackNatively();
            } else if (ACTION_NEXT.equals(action)) {
                playNextTrackNatively();
            } else if (ACTION_PLAY_PAUSE.equals(action)) {
                if (isPlaying) {
                    pauseAudio();
                } else {
                    resumeAudio();
                }
            } else if (ACTION_PLAY.equals(action)) {
                resumeAudio();
            } else if (ACTION_PAUSE.equals(action)) {
                pauseAudio();
            } else if (ACTION_SEEK_TO.equals(action)) {
                long pos = intent.getLongExtra("positionMs", 0);
                seekToAudio(pos);
            } else if (ACTION_PLAY_TRACK.equals(action)) {
                String t = intent.getStringExtra("title");
                String a = intent.getStringExtra("artist");
                String art = intent.getStringExtra("artworkUrl");
                String src = intent.getStringExtra("audioSrc");
                long dur = intent.getLongExtra("durationMs", 0);
                playTrack(t, a, art, src, dur);
            }
        }

        if (intent != null && intent.getAction() != null) {
            try {
                Notification notification = buildNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (Exception e) {
                Log.w("SurmioAudio", "startForeground in onStartCommand: " + e.getMessage());
            }
        }

        return START_STICKY;
    }

    public synchronized void playTrack(String title, String artist, String artworkUrl, String audioSrc, long durMs) {
        if (title != null && !title.isEmpty()) currentTitle = title;
        if (artist != null && !artist.isEmpty()) currentArtist = artist;
        if (durMs > 0) durationMs = durMs;
        currentAudioSrc = audioSrc;

        if (playlist != null && !playlist.isEmpty()) {
            for (int i = 0; i < playlist.size(); i++) {
                TrackItem item = playlist.get(i);
                if ((audioSrc != null && audioSrc.equals(item.audioSrc)) ||
                    (title != null && title.equalsIgnoreCase(item.title))) {
                    currentIndex = i;
                    break;
                }
            }
        } else {
            playlist.add(new TrackItem(currentTitle, currentArtist, artworkUrl, currentAudioSrc, durationMs));
            currentIndex = 0;
        }

        if (artworkUrl != null && !artworkUrl.isEmpty() && !artworkUrl.equals(lastArtworkUrl)) {
            lastArtworkUrl = artworkUrl;
            loadArtworkAsync(artworkUrl);
        }

        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {}

        playNativeAudio(audioSrc);
    }

    private void playNativeAudio(String src) {
        if (src == null || src.isEmpty()) return;
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
                }
            } else {
                mediaPlayer.reset();
            }

            boolean sourceSet = false;
            if (src.startsWith("http://") || src.startsWith("https://")) {
                mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(src));
                sourceSet = true;
            } else {
                String clean = src.replace("https://localhost/", "")
                                  .replace("http://localhost/", "")
                                  .replace("capacitor://localhost/", "")
                                  .replace("file:///android_asset/", "")
                                  .replaceAll("^/+", "");

                int mediaIdx = clean.indexOf("media/");
                if (mediaIdx >= 0) {
                    clean = clean.substring(mediaIdx);
                }
                int qIdx = clean.indexOf('?');
                if (qIdx >= 0) {
                    clean = clean.substring(0, qIdx);
                }

                if (clean.startsWith("media/")) {
                    String assetPath = "public/" + clean;
                    try {
                        AssetFileDescriptor afd = getAssets().openFd(assetPath);
                        mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                        afd.close();
                        sourceSet = true;
                    } catch (Exception e) {
                        // Not bundled in APK assets; stream directly from high-speed remote CDN (lightweight APK mode)
                        String remoteUrl = "https://raw.githubusercontent.com/aditys4444/Surmio-Music-app/main/public/" + clean;
                        try {
                            mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(remoteUrl));
                            sourceSet = true;
                        } catch (Exception netErr) {
                            Log.w("SurmioAudio", "Error setting remote data source: " + netErr.getMessage());
                        }
                    }
                } else {
                    // Local disk file
                    mediaPlayer.setDataSource(src);
                    sourceSet = true;
                }
            }

            if (sourceSet) {
                mediaPlayer.setOnPreparedListener(mp -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentSpeed > 0) {
                        try {
                            mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(currentSpeed));
                        } catch (Exception ignored) {}
                    }
                    mp.start();
                    isPlaying = true;
                    if (mp.getDuration() > 0) {
                        durationMs = mp.getDuration();
                    }
                    positionMs = mp.getCurrentPosition();
                    updatePlaybackState();
                    updateMetadata();
                    Notification notification = buildNotification();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                    updateNotification();
                    notifyUiSync("playing");
                });

                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    positionMs = durationMs;
                    updatePlaybackState();
                    updateNotification();
                    notifyUiSync("ended");
                    playNextTrackNatively();
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.w("SurmioAudio", "Native MediaPlayer warning: " + what + ", " + extra);
                    isPlaying = false;
                    updatePlaybackState();
                    updateNotification();
                    return true;
                });

                mediaPlayer.prepareAsync();
            }
        } catch (Exception e) {
            Log.e("SurmioAudio", "Error configuring MediaPlayer: " + e.getMessage(), e);
        }
    }

    public synchronized void pauseAudio() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.pause();
                positionMs = mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        isPlaying = false;
        updatePlaybackState();
        updateNotification();
        notifyUiSync("pause");
    }

    public synchronized void prepareTrackMetadata(String title, String artist, String artworkUrl, String audioSrc, long durMs) {
        if (title != null && !title.isEmpty()) currentTitle = title;
        if (artist != null && !artist.isEmpty()) currentArtist = artist;
        if (durMs > 0) durationMs = durMs;
        currentAudioSrc = audioSrc;
        if (playlist.isEmpty() && currentAudioSrc != null && !currentAudioSrc.isEmpty()) {
            playlist.add(new TrackItem(currentTitle, currentArtist, artworkUrl, currentAudioSrc, durationMs));
            currentIndex = 0;
        }
        if (artworkUrl != null && !artworkUrl.isEmpty() && !artworkUrl.equals(lastArtworkUrl)) {
            lastArtworkUrl = artworkUrl;
            loadArtworkAsync(artworkUrl);
        }
        updateMetadata();
        updateNotification();
    }

    public synchronized void resumeAudio() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {}

        if (mediaPlayer != null) {
            try {
                mediaPlayer.start();
                isPlaying = true;
                positionMs = mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        } else if (currentAudioSrc != null && !currentAudioSrc.isEmpty()) {
            playNativeAudio(currentAudioSrc);
            return;
        } else if (playlist != null && !playlist.isEmpty()) {
            TrackItem first = playlist.get(currentIndex >= 0 && currentIndex < playlist.size() ? currentIndex : 0);
            playTrack(first.title, first.artist, first.artworkUrl, first.audioSrc, first.durationMs);
            return;
        }
        updatePlaybackState();
        updateNotification();
        notifyUiSync("play");
    }

    public synchronized void seekToAudio(long posMs) {
        positionMs = posMs;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo((int) posMs);
            } catch (Exception ignored) {}
        }
        updatePlaybackState();
        notifyUiSync("seek");
    }

    public synchronized void setSpeed(float speed) {
        currentSpeed = speed;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
            try {
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
            } catch (Exception ignored) {}
        }
    }

    public synchronized void playNextTrackNatively() {
        if (playlist != null && !playlist.isEmpty()) {
            currentIndex = (currentIndex + 1) % playlist.size();
            TrackItem next = playlist.get(currentIndex);
            playTrack(next.title, next.artist, next.artworkUrl, next.audioSrc, next.durationMs);
            notifyUiSync("trackChanged");
        }
    }

    public synchronized void playPrevTrackNatively() {
        if (playlist != null && !playlist.isEmpty()) {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
            TrackItem prev = playlist.get(currentIndex);
            playTrack(prev.title, prev.artist, prev.artworkUrl, prev.audioSrc, prev.durationMs);
            notifyUiSync("trackChanged");
        }
    }

    public synchronized void updatePlaylistFromJson(String queueJson) {
        if (queueJson == null || queueJson.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(queueJson);
            playlist.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String t = obj.optString("title", "Song");
                String a = obj.optString("author", obj.optString("artist", "Artist"));
                String art = obj.optString("thumbnail", "");
                String src = obj.optString("audioSrc", obj.optString("streamUrl", ""));
                long dur = (long) (obj.optDouble("durationSec", 180) * 1000);
                playlist.add(new TrackItem(t, a, art, src, dur));
            }
        } catch (Exception e) {
            Log.w("SurmioAudio", "Error parsing queue JSON: " + e.getMessage());
        }
    }

    public long getNativePosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        return positionMs;
    }

    public long getNativeDuration() {
        if (mediaPlayer != null) {
            try {
                int d = mediaPlayer.getDuration();
                if (d > 0) return d;
            } catch (Exception ignored) {}
        }
        return durationMs;
    }

    public boolean isNativePlaying() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.isPlaying();
            } catch (Exception ignored) {}
        }
        return isPlaying;
    }

    public String getCurrentTrackJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("title", currentTitle);
            obj.put("artist", currentArtist);
            obj.put("artworkUrl", lastArtworkUrl != null ? lastArtworkUrl : "");
            obj.put("audioSrc", currentAudioSrc != null ? currentAudioSrc : "");
            obj.put("isPlaying", isNativePlaying());
            obj.put("positionMs", getNativePosition());
            obj.put("durationMs", getNativeDuration());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void notifyUiSync(String event) {
        if (sUiSyncListener != null) {
            sUiSyncListener.onSyncEvent(event, isNativePlaying(), getNativePosition(), getNativeDuration(), currentTitle, currentArtist, lastArtworkUrl);
        }
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;

        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long pos = getNativePosition();
        float speed = isPlaying ? currentSpeed : 0.0f;
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, pos, speed);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Surmio Music")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, getNativeDuration());
        if (cachedArtwork != null) {
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedArtwork);
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, cachedArtwork);
        }
        mediaSession.setMetadata(metaBuilder.build());
    }

    private void loadArtworkAsync(String urlStr) {
        artworkExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setDoInput(true);
                conn.connect();
                InputStream is = conn.getInputStream();
                bmp = BitmapFactory.decodeStream(is);
                is.close();
            } catch (Exception ignored) {}

            if (bmp != null) {
                int maxDim = 384;
                if (bmp.getWidth() > maxDim || bmp.getHeight() > maxDim) {
                    float ratio = Math.min((float) maxDim / bmp.getWidth(), (float) maxDim / bmp.getHeight());
                    int targetW = Math.max(1, Math.round(bmp.getWidth() * ratio));
                    int targetH = Math.max(1, Math.round(bmp.getHeight() * ratio));
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
                    if (scaled != bmp) {
                        bmp.recycle();
                    }
                    bmp = scaled;
                }
                if (cachedArtwork != null && cachedArtwork != bmp && !cachedArtwork.isRecycled()) {
                    cachedArtwork.recycle();
                }
                cachedArtwork = bmp;
                updateMetadata();
            }
            updateNotification();
        });
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevIntent = PendingIntent.getService(
                this,
                101,
                new Intent(this, BackgroundAudioService.class).setAction(ACTION_PREV),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent playPauseIntent = PendingIntent.getService(
                this,
                102,
                new Intent(this, BackgroundAudioService.class).setAction(ACTION_PLAY_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent nextIntent = PendingIntent.getService(
                this,
                103,
                new Intent(this, BackgroundAudioService.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2);

        builder.setStyle(mediaStyle)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying);

        if (cachedArtwork != null) {
            builder.setLargeIcon(cachedArtwork);
        }

        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevIntent).build());

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseLabel = isPlaying ? "Pause" : "Play";
        builder.addAction(new Notification.Action.Builder(
                playPauseIcon, playPauseLabel, playPauseIntent).build());

        builder.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", nextIntent).build());

        return builder.build();
    }

    private void updateNotification() {
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification());
            } catch (Exception ignored) {}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Surmio Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active playback controls in notification shade and lock screen");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (artworkExecutor != null) {
            artworkExecutor.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
