package com.justdance.remote;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class BotDiscoveryClient {
    private static final String DISCOVERY_REQUEST = "JUST_DANCE_REMOTE_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "JUST_DANCE_REMOTE_BRIDGE_V1";
    private static final int[] PORTS = {3000, 3001};
    private static final int CONNECT_TIMEOUT_MS = 450;
    private static final int READ_TIMEOUT_MS = 700;
    private static final int BROADCAST_TIMEOUT_MS = 2200;
    private static final int OVERALL_TIMEOUT_MS = 8000;
    private static final int WORKERS = 64;

    String findFirst() {
        String discovered = findByBroadcast();
        if (discovered != null) return discovered;

        List<String> baseUrls = candidateBaseUrls();
        if (baseUrls.isEmpty()) return null;

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        int submitted = 0;

        try {
            for (String baseUrl : baseUrls) {
                completion.submit(checkCandidate(baseUrl));
                submitted++;
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OVERALL_TIMEOUT_MS);
            for (int completed = 0; completed < submitted; completed++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;

                Future<String> future = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (future == null) return null;

                String result = future.get();
                if (result != null) return result;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<String> checkCandidate(String baseUrl) {
        return () -> isBot(baseUrl) ? baseUrl : null;
    }

    private boolean isBot(String baseUrl) {
        String body = get(baseUrl + "/api/queue");
        return body.contains("\"queue\"")
                && (body.contains("\"totalSongs\"")
                || body.contains("\"maxQueueSize\"")
                || body.contains("\"botConnected\""));
    }

    private String get(String rawUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return "";
            return readAll(connection.getInputStream());
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    private List<String> candidateBaseUrls() {
        Set<String> urls = new LinkedHashSet<>();
        for (String host : candidateHosts()) {
            for (int port : PORTS) {
                urls.add("http://" + host + ":" + port);
            }
        }
        return new ArrayList<>(urls);
    }

    private Set<String> candidateHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    String local = address.getHostAddress();
                    if (local.startsWith("127.") || local.startsWith("169.254.")) continue;

                    int dot = local.lastIndexOf('.');
                    if (dot <= 0) continue;
                    String prefix = local.substring(0, dot + 1);
                    for (int value = 1; value <= 254; value++) {
                        hosts.add(prefix + value);
                    }
                }
            }
        } catch (Exception ignored) {
            return hosts;
        }
        return hosts;
    }

    private String findByBroadcast() {
        byte[] request = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
        Set<String> targets = broadcastAddresses();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BROADCAST_TIMEOUT_MS);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(350);

            for (int port : PORTS) {
                for (String target : targets) {
                    DatagramPacket packet = new DatagramPacket(
                            request,
                            request.length,
                            InetAddress.getByName(target),
                            port
                    );
                    socket.send(packet);
                }
            }

            while (System.nanoTime() < deadline) {
                byte[] buffer = new byte[2048];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                    String baseUrl = parseDiscoveryResponse(buffer, response.getLength());
                    if (baseUrl != null && isBot(baseUrl)) return baseUrl;
                } catch (Exception ignored) {
                    // Keep listening until the short broadcast window closes.
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    private String parseDiscoveryResponse(byte[] buffer, int length) {
        try {
            String body = new String(buffer, 0, length, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(body);
            if (!DISCOVERY_RESPONSE.equals(json.optString("kind"))) return null;
            String baseUrl = json.optString("baseUrl", "");
            return baseUrl.isEmpty() ? null : baseUrl;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> broadcastAddresses() {
        Set<String> addresses = new LinkedHashSet<>();
        addresses.add("255.255.255.255");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    String local = address.getHostAddress();
                    int dot = local.lastIndexOf('.');
                    if (dot > 0) addresses.add(local.substring(0, dot + 1) + "255");
                }
            }
        } catch (Exception ignored) {
            return addresses;
        }

        return addresses;
    }
}
