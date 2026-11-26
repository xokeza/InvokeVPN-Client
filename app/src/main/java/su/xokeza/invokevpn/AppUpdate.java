package su.xokeza.invokevpn;

import java.net.URL;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONObject;

record AppUpdate(String version, String apkUrl) {
    private static final String REPOSITORY = "xokeza/InvokeVPN-Client";
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}");

    static AppUpdate latest(String installed) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "InvokeVPN-Android");
        try {
            if (connection.getResponseCode() != 200) return null;
            try (var stream = connection.getInputStream()) {
                return fromRelease(new JSONObject(Subscription.read(stream)), installed);
            }
        } finally {
            connection.disconnect();
        }
    }

    static AppUpdate fromRelease(JSONObject release, String installed) {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String tag = release.optString("tag_name");
        if (!tag.startsWith("v")) return null;
        String version = tag.substring(1);
        if (!isNewer(version, installed)) return null;
        String file = "InvokeVPN-" + version + ".apk";
        String url = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + file;
        var assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && file.equals(asset.optString("name"))
                    && "uploaded".equals(asset.optString("state"))
                    && url.equals(asset.optString("browser_download_url"))) {
                return new AppUpdate(version, url);
            }
        }
        return null;
    }

    private static boolean isNewer(String latest, String installed) {
        if (installed == null || !VERSION.matcher(latest).matches() || !VERSION.matcher(installed).matches()) return false;
        String[] candidate = latest.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < candidate.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(candidate[i]), Integer.parseInt(current[i]));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }
}
