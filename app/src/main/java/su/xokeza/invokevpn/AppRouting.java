package su.xokeza.invokevpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;

import java.util.LinkedHashSet;
import java.util.Set;

final class AppRouting {
    private AppRouting() {}

    static Set<String> packages(Store store) {
        return new LinkedHashSet<>(store.preferences.getStringSet("routing_apps", java.util.Collections.emptySet()));
    }

    static void apply(Context context, VpnService.Builder vpn, Store store) throws Exception {
        String mode = store.preferences.getString("app_routing_mode", "preset");
        Set<String> packages = packages(store);
        packages.remove(context.getPackageName());
        if (mode.equals("only")) {
            int added = 0;
            for (String app : packages) {
                try {
                    vpn.addAllowedApplication(app);
                    added++;
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            if (added == 0) throw new IllegalStateException("Выбери хотя бы одно установленное приложение для VPN");
            return;
        }
        vpn.addDisallowedApplication(context.getPackageName());
        if (mode.equals("preset") && store.preferences.getBoolean("routing_enabled", true)
                && store.preferences.getBoolean("split", true)) {
            try (var list = context.getAssets().open("direct-apps.txt")) {
                packages = new LinkedHashSet<>(java.util.Arrays.asList(Subscription.read(list).split("\\R")));
            }
        } else if (!mode.equals("bypass")) {
            return;
        }
        for (String app : packages) {
            if (app.isEmpty()) continue;
            try { vpn.addDisallowedApplication(app); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
    }
}
