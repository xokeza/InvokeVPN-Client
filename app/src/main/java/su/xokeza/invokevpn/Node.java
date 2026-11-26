package su.xokeza.invokevpn;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Node {
    static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList("vless", "trojan", "ss", "hysteria2", "hy2"));
    final String link, protocol, host, name, auth;
    final int port;
    final Map<String, String> params;

    Node(String link) {
        this.link = link.trim();
        URI uri = URI.create(this.link.replace(" ", "%20"));
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        require(SUPPORTED.contains(scheme), "Протокол не поддерживается");
        protocol = scheme.equals("hy2") ? "hysteria2" : scheme;
        name = display(decode(uri.getRawFragment()));
        params = parameters(uri.getRawQuery());
        if (protocol.equals("ss") && uri.getRawUserInfo() == null && uri.getRawAuthority() != null) {
            String decoded = decoded64(uri.getRawAuthority());
            require(decoded.contains("@"), "Неполная ссылка Shadowsocks");
            String rawAuthority = decoded.substring(decoded.lastIndexOf('@') + 1);
            uri = URI.create("ss://" + rawAuthority);
            auth = decoded.substring(0, decoded.lastIndexOf('@'));
        } else {
            auth = decode(uri.getRawUserInfo());
        }
        String parsedHost = uri.getHost();
        require(parsedHost != null && !parsedHost.trim().isEmpty(), "В ссылке отсутствует адрес сервера");
        host = parsedHost.replace("[", "").replace("]", "");
        port = uri.getPort() == -1 ? 443 : uri.getPort();
        require(port > 0 && port <= 65535 && !auth.trim().isEmpty(), "В ссылке отсутствуют параметры доступа");
        outbound(host);
    }

    String title() { return name.trim().isEmpty() ? protocolLabel() : name; }
    String protocolLabel() {
        return switch (protocol) {
            case "ss" -> "Shadowsocks";
            case "hysteria2" -> "Hysteria 2";
            case "trojan" -> "Trojan";
            default -> "VLESS";
        };
    }

    private JSONObject outbound(String endpoint) {
        JSONObject config;
        switch (protocol) {
            case "vless" -> {
                UUID.fromString(auth);
                JSONObject user = Json.obj("id", auth, "encryption", value("encryption", "none"));
                if (!value("flow", "").isEmpty()) put(user, "flow", value("flow", ""));
                config = Json.obj("protocol", "vless", "settings", Json.obj("vnext",
                        Json.arr(Json.obj("address", endpoint, "port", port, "users", Json.arr(user)))));
            }
            case "trojan" -> config = Json.obj("protocol", "trojan", "settings", Json.obj("servers",
                    Json.arr(Json.obj("address", endpoint, "port", port, "password", auth))));
            case "ss" -> {
                require(value("plugin", "").isEmpty(), "Плагины Shadowsocks не поддерживаются");
                String user = auth.contains(":") ? auth : decoded64(auth);
                int divider = user.indexOf(':');
                require(divider > 0 && divider < user.length() - 1, "Неверные параметры Shadowsocks");
                config = Json.obj("protocol", "shadowsocks", "settings", Json.obj("servers", Json.arr(
                        Json.obj("address", endpoint, "port", port, "method", user.substring(0, divider),
                                "password", user.substring(divider + 1)))));
            }
            case "hysteria2" -> {
                require(value("obfs", "").isEmpty(), "Обфускация Hysteria 2 из ссылки не поддерживается");
                config = Json.obj("protocol", "hysteria", "settings", Json.obj("version", 2, "address", endpoint, "port", port));
            }
            default -> throw new IllegalArgumentException("Неизвестный протокол");
        }
        put(config, "tag", "vpn");
        put(config, "streamSettings", stream());
        return config;
    }

    private JSONObject stream() {
        String type = protocol.equals("hysteria2") ? "hysteria" : value("type", "tcp");
        String security = value("security", protocol.equals("trojan") || protocol.equals("hysteria2") ? "tls" : "none");
        JSONObject stream = Json.obj("network", type, "security", security);
        String serverName = value("sni", value("peer", host));
        switch (security) {
            case "tls" -> {
                JSONObject tls = Json.obj("serverName", serverName, "allowInsecure", false);
                String alpn = value("alpn", "");
                if (!alpn.isEmpty()) put(tls, "alpn", Json.arr((Object[]) alpn.split(",")));
                String fingerprint = value("fp", "");
                if (!fingerprint.isEmpty() && !type.equals("hysteria")) put(tls, "fingerprint", fingerprint);
                put(stream, "tlsSettings", tls);
            }
            case "reality" -> {
                require(!value("pbk", "").trim().isEmpty(), "Не указан ключ REALITY");
                JSONObject reality = Json.obj("serverName", serverName, "password", value("pbk", ""),
                        "fingerprint", value("fp", "chrome"), "shortId", value("sid", ""), "spiderX", value("spx", "/"));
                if (params.containsKey("pqv")) put(reality, "mldsa65Verify", params.get("pqv"));
                put(stream, "realitySettings", reality);
            }
            case "none" -> { }
            default -> throw new IllegalArgumentException("Неизвестный тип защиты транспорта");
        }
        String path = value("path", "/");
        switch (type) {
            case "tcp", "raw" -> require(value("headerType", "none").equals("none"), "Маскировка заголовков TCP не поддерживается");
            case "ws" -> put(stream, "wsSettings", Json.obj("path", path, "headers", Json.obj("Host", value("host", serverName))));
            case "httpupgrade" -> put(stream, "httpupgradeSettings", Json.obj("path", path, "host", value("host", serverName)));
            case "grpc" -> {
                JSONObject grpc = Json.obj("serviceName", value("serviceName", ""), "multiMode", value("mode", "").equals("multi"));
                if (params.containsKey("authority")) put(grpc, "authority", params.get("authority"));
                put(stream, "grpcSettings", grpc);
            }
            case "xhttp", "splithttp" -> {
                put(stream, "network", "xhttp");
                JSONObject xhttp = Json.obj("path", path, "host", value("host", ""), "mode", value("mode", "auto"));
                if (params.containsKey("extra")) put(xhttp, "extra", parseObject(params.get("extra")));
                put(stream, "xhttpSettings", xhttp);
            }
            case "hysteria" -> put(stream, "hysteriaSettings", Json.obj("version", 2, "auth", auth));
            default -> throw new IllegalArgumentException("Транспорт не поддерживается");
        }
        if (params.containsKey("fm")) put(stream, "finalmask", parseObject(params.get("fm")));
        return stream;
    }

    String endpointAddress() throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) if (address instanceof Inet4Address) return address.getHostAddress();
        if (addresses.length == 0) throw new UnknownHostException("Адрес узла не найден");
        return addresses[0].getHostAddress();
    }

    private JSONArray transportOutbounds(String endpoint, Store store) {
        var preferences = store.preferences;
        JSONObject proxy = outbound(endpoint);
        if (preferences.getBoolean("mux", false) && protocol.equals("vless") && !value("flow", "").contains("vision")) {
            put(proxy, "mux", Json.obj("enabled", true, "concurrency", 8));
        }
        JSONArray outbounds = Json.arr(proxy);
        boolean fragment = preferences.getBoolean("fragment", false);
        String transport = proxy.optJSONObject("streamSettings").optString("network");
        if (fragment && (transport.equals("tcp") || transport.equals("raw") || transport.equals("ws"))) {
            put(proxy.optJSONObject("streamSettings"), "sockopt", Json.obj("dialerProxy", "fragment"));
            outbounds.put(Json.obj("protocol", "freedom", "tag", "fragment", "settings",
                    Json.obj("fragment", Json.obj("packets", "tlshello", "length", "10-30", "interval", "10-20"))));
        }
        return outbounds;
    }

    String probeConfig(Store store) throws UnknownHostException {
        return Json.obj("log", Json.obj("loglevel", "none"),
                "outbounds", transportOutbounds(endpointAddress(), store)).toString();
    }

    String tunnelConfig(String endpoint, Store store, Subscription profile) {
        ClientPreferences settings = new ClientPreferences(store);
        var preferences = store.preferences;
        boolean routing = preferences.getBoolean("routing_enabled", true);
        boolean sniff = preferences.getBoolean("sniffing", profile.flag("sniffing-enable", true));
        boolean directDomains = routing && preferences.getBoolean("split", true);
        boolean lan = routing && preferences.getBoolean("lan", profile.flag("exclude-local-networks-enable", true));
        JSONObject root = Json.obj("log", Json.obj("loglevel", "none"));
        JSONObject inbound = Json.obj("tag", "android-tun", "protocol", "tun",
                "settings", Json.obj("name", "invokevpn", "MTU", settings.mtu()));
        if (sniff) put(inbound, "sniffing", Json.obj("enabled", true, "routeOnly", true,
                "destOverride", Json.arr("http", "tls", "quic")));
        org.json.JSONArray rules = Json.arr();
        rules.put(Json.obj("type", "field", "outboundTag", "dns-out", "ip", Json.arr("10.64.0.1"),
                "port", "53", "network", "udp,tcp"));
        String[] blocked = settings.rules("routes_block");
        String[] through = settings.rules("routes_vpn");
        String[] direct = settings.rules("routes_direct");
        RoutingRules.append(rules, blocked, "block");
        RoutingRules.append(rules, through, "vpn");
        RoutingRules.append(rules, direct, "direct");
        if (directDomains) rules.put(Json.obj("type", "field", "outboundTag", "direct", "domain",
                Json.arr("domain:ru", "domain:su", "domain:xn--p1ai", "domain:vk.com", "domain:ozon.com", "domain:2gis.com")));
        if (lan) rules.put(Json.obj("type", "field", "outboundTag", "direct", "ip",
                Json.arr("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "fc00::/7", "fe80::/10")));
        JSONArray inbounds = Json.arr(inbound);
        if (preferences.getBoolean("local_proxy", false)) {
            inbounds.put(Json.obj("tag", "local-socks", "listen", "127.0.0.1", "port", 10808,
                    "protocol", "socks", "settings", Json.obj("auth", "noauth", "udp", true)));
            inbounds.put(Json.obj("tag", "local-http", "listen", "127.0.0.1", "port", 10809,
                    "protocol", "http", "settings", Json.obj()));
        }
        put(root, "inbounds", inbounds);
        JSONArray outbounds = transportOutbounds(endpoint, store);
        outbounds.put(Json.obj("protocol", "freedom", "tag", "direct"));
        outbounds.put(Json.obj("protocol", "dns", "tag", "dns-out", "settings", Json.obj("nonIPQuery", "reject")));
        if (blocked.length > 0) outbounds.put(Json.obj("protocol", "blackhole", "tag", "block"));
        put(root, "outbounds", outbounds);
        if (preferences.getBoolean("notification_speed", false)) {
            put(root, "stats", Json.obj());
            put(root, "policy", Json.obj("system", Json.obj("statsOutboundUplink", true, "statsOutboundDownlink", true)));
        }
        put(root, "dns", Json.obj("servers", Json.arr((Object[]) settings.dnsServers()),
                "queryStrategy", settings.ipv6Enabled() ? "UseIP" : "UseIPv4"));
        String strategy = preferences.getString("routing_strategy", "AsIs");
        put(root, "routing", Json.obj("domainStrategy", routing ? strategy : "AsIs", "rules", rules));
        return root.toString();
    }

    String value(String key, String fallback) { return params.getOrDefault(key, fallback); }

    private static JSONObject parseObject(String value) {
        try { return new JSONObject(value); }
        catch (Exception error) { throw new IllegalArgumentException("Неверные параметры транспорта"); }
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (Exception error) { throw new IllegalArgumentException("Неверные параметры узла"); }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static Map<String, String> parameters(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null) return values;
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0) values.put(decode(part.substring(0, separator)), decode(part.substring(separator + 1)));
        }
        return values;
    }

    static String decode(String text) {
        if (text == null) return "";
        return android.net.Uri.decode(text);
    }

    static String decoded64(String text) {
        try { return new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) {
            return new String(Base64.decode(text, Base64.URL_SAFE), StandardCharsets.UTF_8);
        }
    }

    static String display(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
