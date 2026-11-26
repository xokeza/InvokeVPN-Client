package su.xokeza.invokevpn;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class VpnTile extends TileService {
    private boolean listening;
    private final BroadcastReceiver connection = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateState(); }
    };

    @Override public void onStartListening() {
        super.onStartListening();
        if (!listening) {
            IntentFilter filter = new IntentFilter(TunnelService.STATE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(connection, filter, RECEIVER_NOT_EXPORTED);
            else registerReceiver(connection, filter);
            listening = true;
        }
        updateState();
    }

    @Override public void onStopListening() {
        stopUpdates();
        super.onStopListening();
    }

    @Override public void onDestroy() {
        stopUpdates();
        super.onDestroy();
    }

    private void stopUpdates() {
        if (!listening) return;
        unregisterReceiver(connection);
        listening = false;
    }

    private void updateState() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(switch (TunnelService.status) {
            case "connected" -> Tile.STATE_ACTIVE;
            case "connecting" -> Tile.STATE_UNAVAILABLE;
            default -> Tile.STATE_INACTIVE;
        });
        if (Build.VERSION.SDK_INT >= 29) {
            boolean subtitle = new Store(this).preferences.getBoolean("tile_subtitle", true);
            tile.setSubtitle(!subtitle ? null : TunnelService.status.equals("connected") ? "Подключён"
                    : TunnelService.status.equals("connecting") ? "Подключаем…" : "Отключён");
        }
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggle);
        else toggle();
    }

    private void toggle() {
        if (TunnelService.status.equals("connected")) {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.DISCONNECT));
            return;
        }
        if (TunnelService.status.equals("connecting")) return;
        if (VpnService.prepare(this) != null) { openApp(); return; }
        Store store = new Store(this);
        try {
            var profiles = store.all();
            if (profiles.isEmpty()) { openApp(); return; }
            profiles.sort(java.util.Comparator.comparing(profile -> !profile.flag("subscription-pin", false)));
            String selected = store.preferences.getString("selected", "");
            Subscription profile = profiles.stream().filter(item -> item.id.equals(selected)).findFirst().orElse(profiles.get(0));
            if (profile.nodes.isEmpty() || profile.expired() || profile.quotaUsed()) { openApp(); return; }
            int index = Math.max(0, Math.min(profile.nodes.size() - 1, store.preferences.getInt("node:" + profile.id, 0)));
            Intent request = new Intent(this, TunnelService.class).setAction(TunnelService.CONNECT)
                    .putExtra("profile", profile.id).putExtra("node", index);
            startForegroundService(request);
        } catch (RuntimeException error) { openApp(); }
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 18, intent, PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(intent);
    }
}
