package su.xokeza.invokevpn;

import android.os.SystemClock;
import androidcore.Androidcore;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class PingProbe {
    private static final Pattern RTT = Pattern.compile("time[=<]([0-9.]+) ms");

    private PingProbe() {}

    static String method(Store store, Subscription profile) {
        String selected = store.preferences.getString("ping_method", "tcp");
        return selected.equals("default") ? profile.text("ping-type", "tcp") : selected;
    }

    static String url(Store store, Subscription profile) {
        return store.preferences.getString("ping_url", profile.pingUrl());
    }

    static long measure(Store store, Node node, String method, String url, int timeout) {
        try {
            if (method.equals("tcp")) {
                var address = new InetSocketAddress(node.host, node.port);
                long start = SystemClock.elapsedRealtime();
                try (Socket socket = new Socket()) { socket.connect(address, timeout); }
                return Math.max(1, SystemClock.elapsedRealtime() - start);
            }
            if (method.equals("icmp")) {
                Process process = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", Integer.toString((timeout + 999) / 1000), node.host)
                        .redirectErrorStream(true).start();
                try {
                    if (!process.waitFor(timeout, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return -1;
                    try (var output = process.getInputStream()) {
                        var result = RTT.matcher(Subscription.read(output));
                        return result.find() ? Math.max(1, Math.round(Double.parseDouble(result.group(1)))) : -1;
                    }
                } finally { if (process.isAlive()) process.destroy(); }
            }
            return Androidcore.probe(node.probeConfig(store), url, timeout, method.equals("head"));
        } catch (Exception | LinkageError ignored) { return -1; }
    }
}
