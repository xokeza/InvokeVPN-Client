package su.xokeza.invokevpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidcore.Androidcore;

public final class TunnelService extends VpnService {
    static final String CONNECT = "invoke.connect", DISCONNECT = "invoke.disconnect", PING = "invoke.ping";
    static final String STATE = "su.xokeza.invokevpn.STATE";
    static volatile String status = "idle";
    static volatile String connectedName = "";
    static volatile String connectedProfile = "";
    static volatile String failure = "";
    static volatile long since;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probes = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private ParcelFileDescriptor device;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean revoked;
    private volatile Node activeNode;
    private volatile Subscription activeProfile;
    private boolean pinging;
    private String notificationSummary = "";
    private long lastUp, lastDown, sampledAt;
    private final Runnable speed = new Runnable() {
        @Override public void run() {
            Node node = activeNode;
            if (node == null || revoked) return;
            worker.execute(() -> {
                long up = Androidcore.bytes("uplink");
                long down = Androidcore.bytes("downlink");
                main.post(() -> {
                    if (activeNode != node || revoked) return;
                    long now = SystemClock.elapsedRealtime();
                    long elapsed = Math.max(1, now - sampledAt);
                    String rates = "↑ " + Art.bytes(Math.max(0, up - lastUp) * 1000 / elapsed) + "/с  ↓ "
                            + Art.bytes(Math.max(0, down - lastDown) * 1000 / elapsed) + "/с";
                    lastUp = up;
                    lastDown = down;
                    sampledAt = now;
                    getSystemService(NotificationManager.class).notify(13, notification(notificationSummary + " · " + rates));
                    main.postDelayed(this, 3500);
                });
            });
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (DISCONNECT.equals(action)) {
            worker.execute(() -> disconnect(startId, ""));
            return START_NOT_STICKY;
        }
        if (PING.equals(action)) {
            checkActiveNode();
            return START_NOT_STICKY;
        }
        boolean systemStart = SERVICE_INTERFACE.equals(action) || (action == null && Build.VERSION.SDK_INT >= 29 && isAlwaysOn());
        if (!CONNECT.equals(action) && !systemStart) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (systemStart && !status.equals("idle")) return START_STICKY;
        try {
            startForeground(13, notification("Подключаем VPN…"));
        } catch (RuntimeException error) {
            publish("idle", "Android не разрешил запуск VPN");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String profileId = systemStart ? null : intent.getStringExtra("profile");
        final int index = systemStart ? -1 : intent.getIntExtra("node", -1);
        worker.execute(() -> connect(profileId, index, startId, systemStart));
        return systemStart ? START_STICKY : START_REDELIVER_INTENT;
    }

    private void connect(String profileId, int index, int startId, boolean systemStart) {
        try {
            stopEngine();
            publish("connecting", "");
            Store store = new Store(this);
            Subscription profile = systemStart ? store.selected()
                    : store.all().stream().filter(item -> item.id.equals(profileId)).findFirst().orElse(null);
            if (profile == null) throw new IllegalStateException("Подписка отсутствует");
            if (profile.expired()) throw new IllegalStateException("Срок подписки истёк");
            if (profile.quotaUsed()) throw new IllegalStateException("Лимит трафика исчерпан");
            if (systemStart) index = store.selectedIndex(profile);
            if (index < 0 || index >= profile.nodes.size()) throw new IllegalStateException("Выбери узел");
            Node node = profile.nodes.get(index);
            String endpoint = node.endpointAddress();
            ClientPreferences settings = new ClientPreferences(store);
            Builder builder = new Builder()
                    .setSession("InvokeVPN")
                    .setMtu(settings.mtu())
                    .setBlocking(false)
                    .addAddress("10.64.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.64.0.1");
            if (settings.ipv6Enabled()) {
                builder.addAddress("fd66:6543:2189::2", 126).addRoute("::", 0);
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            AppRouting.apply(this, builder, store);
            if (revoked) throw new IllegalStateException("Android отозвал разрешение VPN");
            device = builder.establish();
            if (device == null) throw new IllegalStateException("Разреши создание VPN в Android");
            Androidcore.start(node.tunnelConfig(endpoint, store, profile), device.getFd());
            if (revoked) throw new IllegalStateException("Android отозвал разрешение VPN");
            if (store.preferences.getBoolean("keep_awake", false)) {
                wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "invokevpn:activeTunnel");
                wakeLock.acquire();
            }
            activeNode = node;
            activeProfile = profile;
            connectedProfile = profile.id;
            connectedName = node.title();
            since = android.os.SystemClock.elapsedRealtime();
            notificationSummary = Art.withoutFlag(connectedName);
            publish("connected", "");
            getSystemService(NotificationManager.class).notify(13, notification(notificationSummary));
            if (store.preferences.getBoolean("notification_speed", false)) {
                lastUp = lastDown = 0;
                sampledAt = since;
                main.postDelayed(speed, 3500);
            }
        } catch (Exception | LinkageError error) {
            String message = error instanceof java.net.UnknownHostException ? "Не удалось определить IP-адрес узла"
                    : error instanceof IllegalStateException || error instanceof IllegalArgumentException
                        ? error.getMessage() : "Не удалось запустить туннель Xray";
            disconnect(startId, message);
        }
    }

    private void checkActiveNode() {
        Node node = activeNode;
        Subscription profile = activeProfile;
        if (pinging || !status.equals("connected") || node == null || profile == null) return;
        pinging = true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        String title = Art.withoutFlag(node.title());
        notificationSummary = title + " · Проверяем…";
        manager.notify(13, notification(notificationSummary));
        probes.execute(() -> {
            Store store = new Store(this);
            String method = PingProbe.method(store, profile);
            long ms = PingProbe.measure(store, node, method, PingProbe.url(store, profile), new ClientPreferences(store).pingTimeout(method));
            main.post(() -> {
                pinging = false;
                if (revoked || activeNode != node || !status.equals("connected")) return;
                notificationSummary = title + (ms >= 0 ? " · " + ms + " мс" : " · Нет ответа");
                manager.notify(13, notification(notificationSummary));
            });
        });
    }

    private Notification notification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("tunnel", "Соединение VPN",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 7, new Intent(this, TunnelService.class).setAction(DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent ping = PendingIntent.getService(this, 8, new Intent(this, TunnelService.class).setAction(PING),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, "tunnel").setSmallIcon(R.drawable.ic_status_vpn)
                .setContentTitle("InvokeVPN").setContentText(message).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Отключить", stop).build());
        if (status.equals("connected")) builder.addAction(new Notification.Action.Builder(null, "Пинг", ping).build());
        return builder.build();
    }

    private void stopEngine() {
        main.removeCallbacks(speed);
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
        if (device == null) return;
        try { Androidcore.stop(); }
        catch (LinkageError ignored) { }
        finally {
            try { device.close(); } catch (IOException ignored) { }
            device = null;
        }
    }

    private void publish(String next, String error) {
        status = next;
        failure = error == null ? "" : error;
        if (!next.equals("connected")) {
            activeNode = null;
            activeProfile = null;
            connectedProfile = "";
            connectedName = "";
            since = 0;
        }
        sendBroadcast(new Intent(STATE).setPackage(getPackageName()));
    }

    private void disconnect(int startId, String error) {
        stopEngine();
        publish("idle", error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (startId > 0) stopSelf(startId);
        else stopSelf();
    }

    @Override
    public void onRevoke() {
        revoked = true;
        worker.execute(() -> disconnect(0, ""));
    }

    @Override
    public void onDestroy() {
        revoked = true;
        worker.execute(() -> {
            stopEngine();
            publish("idle", failure);
        });
        worker.shutdown();
        probes.shutdownNow();
        super.onDestroy();
    }
}
