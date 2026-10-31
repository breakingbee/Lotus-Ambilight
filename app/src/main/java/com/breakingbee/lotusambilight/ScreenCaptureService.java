package com.breakingbee.lotusambilight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.breakingbee.lotusambilight.action.START_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "capture_result_code";
    public static final String EXTRA_RESULT_DATA = "capture_result_data";

    private static final String TAG = "LotusAmbilight";
    private static final String CHANNEL_ID = "lotus_screen_capture";
    private static final int NOTIFICATION_ID = 2401;

    public interface ProjectionListener {
        void onProjectionReady(MediaProjection projection);
        void onProjectionError(String error);
    }

    public final class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProjectionListener listener;
    private MediaProjection projection;
    private String lastError;
    private boolean initializationFinished;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            publishError("The capture service received an invalid start request.");
            return START_NOT_STICKY;
        }

        try {
            createNotificationChannel();
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= 33) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultData == null) {
                publishError("Android did not provide the screen-capture permission token.");
                return START_NOT_STICKY;
            }

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                publishError("Android MediaProjection service is unavailable.");
                return START_NOT_STICKY;
            }

            // The foreground service is already running with the mediaProjection type here.
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                publishError("Android returned no MediaProjection token.");
            } else {
                Log.i(TAG, "MediaProjection created inside the mediaProjection foreground service.");
                publishReady(projection);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not initialize media-projection foreground service", e);
            publishError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    public void setProjectionListener(ProjectionListener listener) {
        this.listener = listener;
        dispatchResult();
    }

    private void publishReady(MediaProjection readyProjection) {
        projection = readyProjection;
        lastError = null;
        initializationFinished = true;
        dispatchResult();
    }

    private void publishError(String error) {
        lastError = error;
        initializationFinished = true;
        dispatchResult();
    }

    private void dispatchResult() {
        if (!initializationFinished || listener == null) return;
        ProjectionListener currentListener = listener;
        MediaProjection ready = projection;
        String error = lastError;
        mainHandler.post(() -> {
            if (listener != currentListener) return;
            if (ready != null) {
                currentListener.onProjectionReady(ready);
            } else {
                currentListener.onProjectionError(error == null ? "Unknown capture error." : error);
            }
        });
    }

    public void stopCapture(boolean stopProjection) {
        MediaProjection oldProjection = projection;
        projection = null;
        listener = null;
        if (stopProjection && oldProjection != null) {
            oldProjection.stop();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen colour sync",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps screen capture active while Lotus Ambilight syncs LED colours.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Lotus Ambilight is active")
                .setContentText("Reading screen colours for LED sync")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onDestroy() {
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        listener = null;
        super.onDestroy();
    }
}
