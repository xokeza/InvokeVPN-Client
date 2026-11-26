package su.xokeza.invokevpn;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class SettingsBackup {
    private static final String[] FLAGS = {
        "split", "lan", "routing_enabled", "sniffing", "fragment", "mux", "ipv6", "local_proxy", "keep_awake",
        "notification_speed", "tile_subtitle", "auto_update", "open_update", "ping_on_open", "ping_on_update",
        "notify_refresh", "subscription_hwid", "large_type"
    };
    private static final String[][] CHOICES = {
        {"theme", "rose", "graphite", "violet", "system", "custom"},
        {"auto_connect", "default", "off", "lastused", "lowestdelay", "first"},
        {"routing_strategy", "AsIs", "IPIfNonMatch", "IPOnDemand"},
        {"app_routing_mode", "preset", "all", "bypass", "only"},
        {"vpn_dns", "auto", "cloudflare", "google", "custom"},
        {"node_sort", "default", "none", "name", "ping"},
        {"ping_method", "default", "tcp", "icmp", "proxy", "head"},
        {"ping_display", "default", "time", "icon", "both", "dot"}
    };
    private static final String[] EDITABLE = {
        "theme_json", "routes_block", "routes_vpn", "routes_direct", "vpn_dns_url", "ping_url", "subscription_agent"
    };
    private static final String[] NUMBERS = {"tun_mtu", "update_interval", "ping_timeout"};
    private static final int[][] NUMBER_OPTIONS = {
        {1280, 1360, 1400, 1480, 1500}, {1, 2, 3, 6, 12, 24}, {1000, 3000, 5000, 10000}
    };

    private SettingsBackup() {}

    static String exportFrom(SharedPreferences preferences) throws Exception {
        JSONObject settings = new JSONObject();
        var stored = preferences.getAll();
        for (String flag : FLAGS) if (stored.containsKey(flag)) settings.put(flag, stored.get(flag));
        for (String[] entry : CHOICES) if (stored.containsKey(entry[0])) settings.put(entry[0], stored.get(entry[0]));
        for (String key : EDITABLE) if (stored.containsKey(key)) settings.put(key, stored.get(key));
        for (String key : NUMBERS) if (stored.containsKey(key)) settings.put(key, stored.get(key));
        if (stored.containsKey("routing_apps")) settings.put("routing_apps", new JSONArray(preferences.getStringSet("routing_apps",
                java.util.Collections.emptySet())));
        return Json.obj("format", "invokevpn-settings", "version", 1, "settings", settings).toString(2);
    }

    static void restoreTo(String text, SharedPreferences preferences) throws Exception {
        JSONObject envelope = new JSONObject(text);
        if (!envelope.getString("format").equals("invokevpn-settings") || envelope.getInt("version") != 1) {
            throw new IllegalArgumentException("Формат настроек не поддерживается");
        }
        JSONObject data = envelope.getJSONObject("settings");
        if ((data.optString("theme").equals("custom") && !data.has("theme_json"))
                || (data.optString("vpn_dns").equals("custom") && !data.has("vpn_dns_url"))) {
            throw new IllegalArgumentException("В файле отсутствуют параметры выбранной темы или DNS");
        }
        var update = preferences.edit();
        for (String key : FLAGS) {
            if (!data.has(key)) update.remove(key);
            else if (data.get(key) instanceof Boolean value) update.putBoolean(key, value);
            else throw new IllegalArgumentException("Поле должно быть переключателем: " + key);
        }
        for (String[] row : CHOICES) {
            String key = row[0];
            if (!data.has(key)) { update.remove(key); continue; }
            String value = data.getString(key);
            if (!Arrays.asList(row).subList(1, row.length).contains(value)) throw new IllegalArgumentException("Неизвестный режим: " + key);
            update.putString(key, value);
        }
        for (int i = 0; i < NUMBERS.length; i++) {
            String key = NUMBERS[i];
            if (!data.has(key)) { update.remove(key); continue; }
            int value = data.getInt(key);
            if (!contains(NUMBER_OPTIONS[i], value)) throw new IllegalArgumentException("Значение вне диапазона: " + key);
            update.putInt(key, value);
        }
        for (String key : EDITABLE) {
            if (!data.has(key)) { update.remove(key); continue; }
            String value = data.getString(key);
            if (key.startsWith("routes_")) value = RoutingRules.normalize(value);
            else if (key.equals("theme_json")) value = Palette.fromJson(value).values.toString();
            else if (key.endsWith("_url")) {
                var url = Subscription.https(value);
                if (url.getFragment() != null) throw new IllegalArgumentException("HTTPS-адрес содержит фрагмент");
                value = url.toASCIIString();
            } else if (value.length() > 180 || !value.matches("[ -~]*")) {
                throw new IllegalArgumentException("Некорректный User-Agent");
            }
            update.putString(key, value);
        }
        if (data.has("routing_apps")) {
            JSONArray apps = data.getJSONArray("routing_apps");
            if (apps.length() > 500) throw new IllegalArgumentException("Слишком много приложений");
            Set<String> values = new HashSet<>();
            for (int i = 0; i < apps.length(); i++) {
                String id = apps.getString(i);
                if (!id.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*") || id.length() > 255) {
                    throw new IllegalArgumentException("Некорректное имя пакета");
                }
                values.add(id);
            }
            update.putStringSet("routing_apps", values);
        } else {
            update.remove("routing_apps");
        }
        if (!update.commit()) throw new IllegalStateException("Не удалось сохранить настройки");
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (expected == value) return true;
        return false;
    }
}
