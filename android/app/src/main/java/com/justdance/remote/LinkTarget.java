package com.justdance.remote;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides which addresses the app may link to and where the saved token may be sent. */
final class LinkTarget {
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_]{1,25}");
    private static final Pattern CHANNEL_PATH = Pattern.compile("^/c/([A-Za-z0-9_]{1,25})(/.*)?$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private LinkTarget() {
    }

    /**
     * Builds the base URL for a hosted bot: https://site/c/channel. The site may be a bare host,
     * an https URL, or the full /c/channel link (then the channel field can stay empty).
     * Throws IllegalArgumentException with a message for the user.
     */
    static String cloudBaseUrl(String rawSite, String rawChannel) {
        String site = rawSite == null ? "" : rawSite.trim();
        if (site.isEmpty()) throw new IllegalArgumentException("Enter the website, for example jd.example.com.");
        if (site.toLowerCase(Locale.ROOT).startsWith("http://")) {
            throw new IllegalArgumentException("Cloud sites must use https://.");
        }
        if (!site.contains("://")) site = "https://" + site;

        URI uri;
        try {
            uri = new URI(site);
        } catch (Exception error) {
            throw new IllegalArgumentException("That website address could not be read.");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("Cloud sites must use https://.");
        if (host == null || host.isEmpty() || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("That website address could not be read.");
        }

        String channel = rawChannel == null ? "" : rawChannel.trim();
        if (channel.isEmpty()) {
            Matcher matcher = CHANNEL_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (matcher.matches()) channel = matcher.group(1);
        }
        while (channel.startsWith("@") || channel.startsWith("#")) channel = channel.substring(1);
        channel = channel.toLowerCase(Locale.ROOT);
        if (channel.isEmpty()) throw new IllegalArgumentException("Enter your Twitch channel name.");
        if (!CHANNEL.matcher(channel).matches()) throw new IllegalArgumentException("That isn't a Twitch channel name.");

        String port = uri.getPort() > 0 && uri.getPort() != 443 ? ":" + uri.getPort() : "";
        return "https://" + host.toLowerCase(Locale.ROOT) + port + "/c/" + channel;
    }

    /** Splits a cloud base URL back into {site, channel} for the link form. */
    static String[] cloudParts(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl;
        int index = value.lastIndexOf("/c/");
        if (!value.startsWith("https://") || index < 0) return new String[]{"", ""};
        return new String[]{value.substring(0, index), value.substring(index + 3)};
    }

    /** True for a literal private IPv4 address: 10/8, 172.16/12 or 192.168/16. */
    static boolean isPrivateIpv4(String host) {
        Matcher matcher = IPV4.matcher(host == null ? "" : host);
        if (!matcher.matches()) return false;
        int a = Integer.parseInt(matcher.group(1));
        int b = Integer.parseInt(matcher.group(2));
        int c = Integer.parseInt(matcher.group(3));
        int d = Integer.parseInt(matcher.group(4));
        if (a > 255 || b > 255 || c > 255 || d > 255) return false;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    /**
     * Checks a base URL advertised in a discovery reply. It is only accepted when it is plain
     * http://ip:port on a private address and that address is the one the reply came from, so a
     * device on the network can't point the app at some other host. Returns null when refused.
     */
    static String discoveredBaseUrl(String advertised, String sourceAddress) {
        try {
            URI uri = new URI(advertised == null ? "" : advertised.trim());
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return null;
            if (!isPrivateIpv4(host) || !host.equals(sourceAddress)) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return null;
            if (uri.getQuery() != null || uri.getFragment() != null) return null;
            int port = uri.getPort();
            if (port == -1) return "http://" + host;
            if (port < 1 || port > 65535) return null;
            return "http://" + host + ":" + port;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * The token travels in a request header, so it is only sent over https, or over plain http to
     * an address on the local network. Resolves the host: call it off the main thread.
     */
    static boolean mayCarryToken(String baseUrl) {
        try {
            URI uri = new URI(baseUrl);
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isLocalAddress(address)) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isLocalAddress(InetAddress address) {
        if (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) return true;
        // fc00::/7, the IPv6 counterpart of the private IPv4 ranges.
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }
}
