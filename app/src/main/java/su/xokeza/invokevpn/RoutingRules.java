package su.xokeza.invokevpn;

import org.json.JSONArray;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class RoutingRules {
    private RoutingRules() {}

    static String normalize(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : text.split("[\\s,;]+")) {
            if (entry.isEmpty()) continue;
            boolean exact = entry.startsWith("full:");
            boolean suffix = entry.startsWith("domain:");
            if (!exact && !suffix && (entry.contains("/") || entry.contains(":") || entry.matches("[0-9]+(\\.[0-9]+){3}"))) {
                result.add(network(entry));
            } else {
                String domain = exact ? entry.substring(5) : suffix ? entry.substring(7) : entry;
                if (domain.startsWith("*.")) domain = domain.substring(2);
                if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
                try {
                    domain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                    if (domain.isEmpty() || domain.length() > 253 || (!suffix && !exact && !domain.contains("."))) {
                        throw new IllegalArgumentException();
                    }
                    result.add((exact ? "full:" : "domain:") + domain);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("Некорректный домен: " + entry);
                }
            }
            if (result.size() > 100) throw new IllegalArgumentException("До 100 правил на список");
        }
        return String.join("\n", result);
    }

    private static String network(String entry) {
        String[] parts = entry.split("/", -1);
        try {
            String address = parts[0];
            boolean ipv6 = address.contains(":");
            if (parts.length > 2 || !(ipv6 ? address.matches("[0-9A-Fa-f:.]+")
                    : address.matches("[0-9]+(\\.[0-9]+){3}"))) throw new IllegalArgumentException();
            InetAddress ip = InetAddress.getByName(address);
            if (ipv6 && !(ip instanceof Inet6Address)) throw new IllegalArgumentException();
            if (parts.length == 1) return ip.getHostAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > (ipv6 ? 128 : 32)) throw new IllegalArgumentException();
            return ip.getHostAddress() + "/" + prefix;
        } catch (Exception error) {
            throw new IllegalArgumentException("Некорректный IP или CIDR: " + entry);
        }
    }

    static void append(JSONArray rules, String[] entries, String outbound) {
        JSONArray domains = new JSONArray();
        JSONArray ips = new JSONArray();
        for (String entry : entries) {
            if (entry.startsWith("full:") || entry.startsWith("domain:")) domains.put(entry);
            else if (entry.contains(":") || entry.contains("/") || entry.matches("[0-9]+(\\.[0-9]+){3}")) ips.put(entry);
            else domains.put("domain:" + entry);
        }
        if (domains.length() > 0) rules.put(Json.obj("type", "field", "outboundTag", outbound, "domain", domains));
        if (ips.length() > 0) rules.put(Json.obj("type", "field", "outboundTag", outbound, "ip", ips));
    }
}
