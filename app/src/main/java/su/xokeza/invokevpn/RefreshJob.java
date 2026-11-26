package su.xokeza.invokevpn;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class RefreshJob extends JobService {
    private static final int ID = 8446;
    private static final Set<String> RUNNING = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> current;

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) == null) {
            scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, RefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(true).build());
        }
    }

    static Subscription refresh(Context context, Store store, String source, Subscription previous) throws Exception {
        synchronized (RUNNING) {
            if (!RUNNING.add(source)) throw new IllegalStateException("Подписка уже обновляется");
        }
        try {
            Subscription profile = Subscription.fetch(source, previous, store);
            store.save(profile, previous != null);
            if (previous != null && store.preferences.getBoolean("notify_refresh", false)) {
                try { notifyRefresh(context, profile); }
                catch (RuntimeException ignored) { }
            }
            return profile;
        } finally {
            synchronized (RUNNING) { RUNNING.remove(source); }
        }
    }

    private static void notifyRefresh(Context context, Subscription profile) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("updates", "Обновления подписок", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(context, 54, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        manager.notify(profile.id, ID + 1, new Notification.Builder(context, "updates").setSmallIcon(R.drawable.ic_status_vpn)
                .setContentTitle("Подписка обновлена").setContentText(profile.text("profile-title", "InvokeVPN"))
                .setContentIntent(open).setAutoCancel(true).build());
    }

    static void expiry(Context context, Store store, Subscription profile) {
        long expiry = profile.userinfo("expire");
        long remaining = expiry - System.currentTimeMillis() / 1000;
        if (!profile.flag("notification-subs-expire", false) || remaining <= 0 || remaining > 3 * 86400) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        String key = "expiry:" + profile.id;
        if (store.preferences.getLong(key, 0) == expiry) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("expiry", "Срок подписки", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
        String url = profile.link("sub-expire-button-link");
        Intent intent = url.isEmpty() ? new Intent(context, MainActivity.class) : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PendingIntent open = PendingIntent.getActivity(context, profile.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(profile.id, ID, new Notification.Builder(context, "expiry").setSmallIcon(R.drawable.ic_invoke)
                .setContentTitle("Подписка скоро закончится")
                .setContentText(profile.text("profile-title", "InvokeVPN")).setContentIntent(open).setAutoCancel(true).build());
        store.preferences.edit().putLong(key, expiry).apply();
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        current = worker.submit(() -> {
            try {
                Store store = new Store(this);
                ClientPreferences settings = new ClientPreferences(store);
                for (Subscription profile : store.all()) {
                    if (Thread.currentThread().isInterrupted()) break;
                    expiry(this, store, profile);
                    if (profile.source.isEmpty() || !settings.refreshOnSchedule(profile)
                            || System.currentTimeMillis() - profile.refreshedAt < settings.refreshHours(profile) * 3600000L) continue;
                    try { refresh(this, store, profile.source, profile); }
                    catch (Exception ignored) { }
                }
            } finally {
                if (!Thread.currentThread().isInterrupted()) jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (current != null) current.cancel(true);
        return true;
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
