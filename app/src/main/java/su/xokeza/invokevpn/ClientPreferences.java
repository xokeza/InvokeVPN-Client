package su.xokeza.invokevpn;

import android.content.SharedPreferences;

import java.util.Arrays;

final class ClientPreferences {
    private final SharedPreferences values;

    ClientPreferences(Store store) { values = store.preferences; }

    int refreshHours(Subscription profile) {
        int hours = values.getInt("update_interval", 0);
        return hours == 0 ? profile.interval() : hours;
    }

    boolean refreshOnSchedule(Subscription profile) {
        return values.getBoolean("auto_update", profile.flag("subscription-auto-update-enable", true));
    }

    boolean refreshOnOpen(Subscription profile) {
        return values.getBoolean("open_update", profile.flag("update-always", false)
                || profile.flag("subscription-auto-update-open-enable", false));
    }

    String sort(Subscription profile) {
        String mode = values.getString("node_sort", "default");
        return mode.equals("default") ? profile.text("subscriptions-sort-type", "none") : mode;
    }

    String autoConnect(Subscription profile) {
        String mode = values.getString("auto_connect", "default");
        if (!mode.equals("default")) return mode;
        return profile.flag("subscription-autoconnect", false)
                ? profile.text("subscription-autoconnect-type", "lastused") : "off";
    }

    String pingDisplay(Subscription profile) {
        String mode = values.getString("ping_display", "default");
        return mode.equals("default") ? profile.text("ping-result", "time") : mode;
    }

    int pingTimeout(String method) {
        int fallback = method.equals("tcp") ? 3500 : method.equals("icmp") ? 4000 : 5000;
        return values.getInt("ping_timeout", fallback);
    }

    String[] dnsServers() {
        return switch (values.getString("vpn_dns", "auto")) {
            case "cloudflare" -> new String[]{"https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"};
            case "google" -> new String[]{"https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query"};
            case "custom" -> new String[]{values.getString("vpn_dns_url", "https://1.1.1.1/dns-query")};
            default -> new String[]{"https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"};
        };
    }

    boolean ipv6Enabled() { return values.getBoolean("ipv6", false); }

    int mtu() { return values.getInt("tun_mtu", 1400); }

    String[] rules(String key) {
        if (!values.getBoolean("routing_enabled", true)) return new String[0];
        return Arrays.stream(values.getString(key, "").split("\\R"))
                .filter(part -> !part.isEmpty()).toArray(String[]::new);
    }
}
