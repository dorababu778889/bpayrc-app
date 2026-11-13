package in.bpaypan.ccpay;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BpayNotificationService extends Service {

    private static final String SYNC_CHANNEL_ID = "bpay_bg_sync_channel";
    private static final String ALERT_CHANNEL_ID = "bpay_high_alerts_channel";
    private static final int FOREGROUND_ID = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String lastRegisteredMobile = null;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            executor.execute(() -> checkServerForNotifications());
            handler.postDelayed(this, 12000); // Poll every 12 seconds
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels(this);
        startForeground(FOREGROUND_ID, buildSilentForegroundNotification());
        handler.post(pollRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void checkServerForNotifications() {
        try {
            SharedPreferences prefs = getSharedPreferences("bpay_prefs", Context.MODE_PRIVATE);
            String deviceId = prefs.getString("device_id", null);
            if (deviceId == null) {
                deviceId = UUID.randomUUID().toString();
                prefs.edit().putString("device_id", deviceId).apply();
            }

            String userMobile = prefs.getString("user_mobile", "");
            String virtualEndpoint = MainActivity.PORTAL_URL + "/api/a1-callback?device=" + deviceId;

            if (!userMobile.equals(lastRegisteredMobile)) {
                registerDeviceSubscription(virtualEndpoint, userMobile);
                lastRegisteredMobile = userMobile;
            }

            // 1. Check /api/settings for live website logo & latest broadcast_push
            String settingsJsonStr = httpGet(MainActivity.PORTAL_URL + "/api/settings");
            if (settingsJsonStr != null && !settingsJsonStr.isEmpty()) {
                JSONObject settingsObj = new JSONObject(settingsJsonStr);
                String logoId = settingsObj.optString("logo_id", "");
                File logoFile = new File(getFilesDir(), "bpay_live_logo.png");
                if (!logoId.isEmpty()) {
                    String savedLogoId = prefs.getString("cached_logo_id", "");
                    if (!logoId.equals(savedLogoId) || !logoFile.exists()) {
                        Bitmap bmp = downloadBitmap(MainActivity.PORTAL_URL + "/img/" + logoId);
                        if (bmp != null) {
                            try (FileOutputStream fos = new FileOutputStream(logoFile)) {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            }
                            prefs.edit().putString("cached_logo_id", logoId).apply();
                        }
                    }
                }

                String broadcastRaw = settingsObj.optString("broadcast_push", "");
                if (!broadcastRaw.isEmpty()) {
                    JSONObject bp = new JSONObject(broadcastRaw);
                    String bpId = bp.optString("id", "");
                    String bpTitle = bp.optString("title", "");
                    String bpMsg = bp.optString("message", "");
                    String lastSeenBpId = prefs.getString("last_broadcast_id", "");
                    if (!bpId.isEmpty() && !bpId.equals(lastSeenBpId)) {
                        prefs.edit().putString("last_broadcast_id", bpId).apply();
                        if (!lastSeenBpId.isEmpty() && !bpTitle.isEmpty()) {
                            fireNativeNotification(this, bpTitle, bpMsg);
                        }
                    }
                }
            }

            // 2. Check /api/latest-push-payload for targeted user notifications
            String encodedEp = URLEncoder.encode(virtualEndpoint, "UTF-8");
            String pushJsonStr = httpGet(MainActivity.PORTAL_URL + "/api/latest-push-payload?endpoint=" + encodedEp);
            if (pushJsonStr != null && !pushJsonStr.isEmpty()) {
                JSONObject pushObj = new JSONObject(pushJsonStr);
                String title = pushObj.optString("title", "");
                String body = pushObj.optString("body", "");
                if (!title.isEmpty() && !title.equals("Bpay Payments Alert")) {
                    String signature = title + "|" + body;
                    String lastSig = prefs.getString("last_push_sig", "");
                    if (!signature.equals(lastSig)) {
                        prefs.edit().putString("last_push_sig", signature).apply();
                        if (!lastSig.isEmpty()) {
                            fireNativeNotification(this, title, body);
                        }
                    }
                }
            }
        } catch (Exception e) {}
    }

    private void registerDeviceSubscription(String endpoint, String mobile) {
        try {
            URL url = new URL(MainActivity.PORTAL_URL + "/api/save-push-sub");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            JSONObject subObj = new JSONObject();
            subObj.put("endpoint", endpoint);
            JSONObject payload = new JSONObject();
            payload.put("subscription", subObj);
            payload.put("mobile", mobile);
            payload.put("role", "USER");

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.close();
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {}
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private Bitmap downloadBitmap(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoInput(true);
            conn.connect();
            InputStream input = conn.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            return null;
        }
    }

    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel syncChannel = new NotificationChannel(
                    SYNC_CHANNEL_ID,
                    "BPAY Background Sync",
                    NotificationManager.IMPORTANCE_MIN
            );
            syncChannel.setShowBadge(false);
            nm.createNotificationChannel(syncChannel);

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttr = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "BPAY RC Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Instant transaction & offer alerts from BPAY RC");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 250, 150, 250});
            alertChannel.setSound(soundUri, audioAttr);
            nm.createNotificationChannel(alertChannel);
        }
    }

    private Notification buildSilentForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
                .setContentTitle("BPAY RC")
                .setContentText("Real-time alert service active")
                .setSmallIcon(R.drawable.ic_stat_bpay)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    public static void fireNativeNotification(Context context, String title, String message) {
        createNotificationChannels(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) System.currentTimeMillis(), intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT) : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Bitmap largeLogo = null;
        File liveLogoFile = new File(context.getFilesDir(), "bpay_live_logo.png");
        if (liveLogoFile.exists()) {
            largeLogo = BitmapFactory.decodeFile(liveLogoFile.getAbsolutePath());
        }
        if (largeLogo == null) {
            largeLogo = BitmapFactory.decodeResource(context.getResources(), R.drawable.ic_launcher_bpay);
        }

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bpay)
                .setLargeIcon(largeLogo)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 250, 150, 250})
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? (PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_ONE_SHOT
        );
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000, restartServicePendingIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
