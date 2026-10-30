package com.surmio.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    public static final String ACTION_UPDATE_TRACK = "com.surmio.music.ACTION_UPDATE_TRACK";

    public interface MediaControlListener {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
        void onSeekTo(long posMs);
    }

    private static MediaControlListener controlListener;

    public static void setControlListener(MediaControlListener listener) {
        controlListener = listener;
    }

    private PowerManager.WakeLock wakeLock;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private ExecutorService artworkExecutor;

    private String currentTitle = "Surmio Music";
    private String currentArtist = "Continuous Studio Playback";
    private String lastArtworkUrl = null;
    private Bitmap cachedArtwork = null;
    private boolean isPlaying = false;
    private long durationMs = 0;
    private long positionMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
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
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "SurmioMusicSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                isPlaying = true;
                updatePlaybackState();
                updateNotification();
                if (controlListener != null) controlListener.onPlay();
            }

            @Override
            public void onPause() {
                isPlaying = false;
                updatePlaybackState();
                updateNotification();
                if (controlListener != null) controlListener.onPause();
            }

            @Override
            public void onSkipToNext() {
                if (controlListener != null) controlListener.onNext();
            }

            @Override
            public void onSkipToPrevious() {
                if (controlListener != null) controlListener.onPrev();
            }

            @Override
            public void onSeekTo(long pos) {
                positionMs = pos;
                updatePlaybackState();
                if (controlListener != null) controlListener.onSeekTo(pos);
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PREV.equals(action)) {
                if (controlListener != null) controlListener.onPrev();
            } else if (ACTION_NEXT.equals(action)) {
                if (controlListener != null) controlListener.onNext();
            } else if (ACTION_PLAY_PAUSE.equals(action)) {
                if (isPlaying) {
                    isPlaying = false;
                    updatePlaybackState();
                    updateNotification();
                    if (controlListener != null) controlListener.onPause();
                } else {
                    isPlaying = true;
                    updatePlaybackState();
                    updateNotification();
                    if (controlListener != null) controlListener.onPlay();
                }
            } else if (ACTION_PLAY.equals(action)) {
                isPlaying = true;
                updatePlaybackState();
                updateNotification();
                if (controlListener != null) controlListener.onPlay();
            } else if (ACTION_PAUSE.equals(action)) {
                isPlaying = false;
                updatePlaybackState();
                updateNotification();
                if (controlListener != null) controlListener.onPause();
            } else if (ACTION_UPDATE_TRACK.equals(action)) {
                String t = intent.getStringExtra("title");
                String a = intent.getStringExtra("artist");
                String art = intent.getStringExtra("artworkUrl");
                if (t != null && !t.isEmpty()) currentTitle = t;
                if (a != null && !a.isEmpty()) currentArtist = a;
                isPlaying = intent.getBooleanExtra("isPlaying", isPlaying);
                durationMs = intent.getLongExtra("durationMs", durationMs);
                positionMs = intent.getLongExtra("positionMs", positionMs);

                updatePlaybackState();
                updateMetadata();

                if (art != null && !art.isEmpty() && !art.equals(lastArtworkUrl)) {
                    lastArtworkUrl = art;
                    loadArtworkAsync(art);
                } else {
                    updateNotification();
                }
            }
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        return START_STICKY;
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
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Surmio Music")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
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
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
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
