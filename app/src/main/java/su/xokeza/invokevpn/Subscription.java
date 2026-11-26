package su.xokeza.invokevpn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

final class Subscription {
    static final String HOME = "https://xokeza.su/invokevpn/";
    static final String DEFAULT_PING = "https://cp.cloudflare.com/generate_204";
    static final int MAX_BYTES = 1 << 20;
    final String id, source;
    final long refreshedAt;
    final JSONObject metadata;
    final List<Node> nodes;

    Subscription(String id, String source, long refreshedAt, JSONObject metadata, List<Node> nodes) {
        this.id = id;
        this.source = source;
        this.refreshedAt = refreshedAt;
        this.metadata = metadata;
        this.nodes = nodes;
    }

    static String source(String input) {
        String text = input.trim();
        if (text.regionMatches(true, 0, "invokevpn://sub=", 0, 16)) text = text.substring(16);
        if (text.matches("(?i)[a-f0-9]{64}")) return HOME + "sub/?token=" + text.toLowerCase(Locale.ROOT) + "&format=invokevpn";
        URI uri = https(text);
        if (uri.getFragment() != null) throw new IllegalArgumentException("У ссылки подписки не должно быть фрагмента");
        String address = uri.toASCIIString();
        if ("xokeza.su".equalsIgnoreCase(uri.getHost())
                && ("/invokevpn/sub/".equals(uri.getPath()) || "/invokevpn/subscription.php".equals(uri.getPath()))
                && uri.getQuery() != null && uri.getQuery().matches("(?i)token=[a-f0-9]{64}")) {
            return address + "&format=invokevpn";
        }
        return address;
    }

    static URI https(String input) {
        URI url;
        try { url = URI.create(input); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Некорректная ссылка"); }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
            throw new IllegalArgumentException("Нужна HTTPS-ссылка или ключ InvokeVPN");
        }
        return url;
    }

    static Subscription fetch(String address, Subscription previous, Store store) throws Exception {
        String source = source(address);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(source).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", store.preferences.getString("subscription_agent", "InvokeVPN/1.0 Android"));
        connection.setRequestProperty("Accept", "application/json, text/plain");
        boolean providerRequestedId = previous != null && source.equals(previous.source)
                && previous.flag("subscription-always-hwid-enable", false);
        if (store.preferences.getBoolean("subscription_hwid", providerRequestedId)) {
            connection.setRequestProperty("X-HWID", store.deviceId());
        }
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalArgumentException("Сервер подписки ответил HTTP " + status);
            Map<String, String> headers = new LinkedHashMap<>();
            connection.getHeaderFields().forEach((key, values) -> {
                if (key != null && values.size() > 0) headers.put(key.toLowerCase(Locale.ROOT), values.get(0));
            });
            try (InputStream stream = connection.getInputStream()) {
                return parse(read(stream), source, previous == null ? UUID.randomUUID().toString() : previous.id, headers);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String read(InputStream stream) throws Exception {
        if (stream == null) throw new IllegalArgumentException("Файл недоступен");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (result.size() + count > MAX_BYTES) throw new IllegalArgumentException("Файл больше 1 МБ");
            result.write(buffer, 0, count);
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
    }

    static Subscription parse(String content, String source, String id, Map<String, String> headers) throws Exception {
        String data = content.trim();
        if (!data.startsWith("{") && !data.startsWith("#") && !data.contains("://")) {
            data = Node.decoded64(data).trim();
        }
        JSONObject metadata;
        List<String> links = new ArrayList<>();
        if (data.startsWith("{")) {
            JSONObject root = new JSONObject(data);
            JSONObject nested = root.optJSONObject("metadata");
            metadata = nested == null ? new JSONObject() : new JSONObject(nested.toString());
            JSONArray servers = root.getJSONArray("servers");
            for (int i = 0; i < servers.length(); i++) {
                Object item = servers.get(i);
                if (item instanceof String) links.add((String) item);
                else if (item instanceof JSONObject) {
                    JSONObject node = (JSONObject) item;
                    if (!booleanValue(node.opt("skip"), false)) links.add(node.optString("uri"));
                }
            }
        } else {
            metadata = new JSONObject();
            for (String part : data.split("\\R")) {
                String line = part.trim();
                if (line.startsWith("#")) {
                    int colon = line.indexOf(':');
                    if (colon > 1) metadata.put(line.substring(1, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                } else if (!line.isEmpty()) {
                    links.add(line);
                }
            }
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("subscription-") || key.startsWith("profile-") || key.startsWith("hide")
                    || key.equals("support-url") || key.startsWith("announce") || key.equals("ping-type")
                    || key.equals("check-url-via-proxy") || key.equals("ping-result") || key.equals("sniffing-enable")) {
                if (key.equals("subscription-userinfo") && metadata.optJSONObject(key) != null) continue;
                metadata.put(key, entry.getValue());
            }
        }
        List<Node> nodes = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        boolean filter = !booleanValue(metadata.opt("dont-use-filter"), false);
        for (String link : links) {
            if (nodes.size() >= 150) break;
            try {
                Node node = new Node(link);
                if (node.host.equals("0.0.0.0")) continue;
                if (filter && seen.putIfAbsent(node.link, true) != null) continue;
                nodes.add(node);
            } catch (IllegalArgumentException ignored) { }
        }
        if (nodes.isEmpty() && metadata.length() == 0) {
            throw new IllegalArgumentException("В файле нет поддерживаемых узлов");
        }
        return new Subscription(id, source, System.currentTimeMillis(), metadata, nodes);
    }

    static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) return fallback;
        return switch (String.valueOf(value).trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> fallback;
        };
    }

    boolean flag(String key, boolean fallback) { return booleanValue(metadata.opt(key), fallback); }

    String text(String key, String fallback) {
        String value = metadata.optString(key, fallback);
        if (value.startsWith("base64:")) {
            try { value = Node.decoded64(value.substring(7)); }
            catch (IllegalArgumentException ignored) { return fallback; }
        }
        if (key.equals("announce")) {
            String clean = value.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n')
                    .replaceAll("[\\p{Cntrl}&&[^\n]]", " ").trim();
            return clean.substring(0, Math.min(clean.length(), 800));
        }
        return Node.display(value);
    }

    String link(String key) {
        String url = metadata.optString(key, "");
        try { return https(url).toASCIIString(); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    String pingUrl() {
        String url = link("check-url-via-proxy");
        return url.isEmpty() ? DEFAULT_PING : url;
    }

    int interval() {
        int interval = metadata.optInt("profile-update-interval", 1);
        return interval == 6 || interval == 12 || interval == 24 ? interval : 1;
    }

    long userinfo(String key) {
        JSONObject data = metadata.optJSONObject("subscription-userinfo");
        if (data != null) return Math.max(0, data.optLong(key, 0));
        for (String entry : metadata.optString("subscription-userinfo").split(";")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(key)) {
                try { return Math.max(0, Long.parseLong(pair[1].trim())); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    boolean hasUserinfo() { return metadata.has("subscription-userinfo"); }

    boolean expired() {
        long expiry = userinfo("expire");
        return expiry > 0 && expiry <= System.currentTimeMillis() / 1000;
    }

    boolean quotaUsed() {
        long total = userinfo("total");
        return total > 0 && (double) userinfo("upload") + userinfo("download") >= total;
    }

    JSONObject stored() {
        JSONArray links = Json.arr();
        for (Node node : nodes) links.put(node.link);
        return Json.obj("id", id, "source", source, "refreshedAt", refreshedAt, "metadata", metadata, "servers", links);
    }

    static Subscription restore(JSONObject object) throws Exception {
        JSONArray links = object.getJSONArray("servers");
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < links.length(); i++) nodes.add(new Node(links.getString(i)));
        return new Subscription(object.getString("id"), object.optString("source"), object.getLong("refreshedAt"),
                object.getJSONObject("metadata"), nodes);
    }
}
