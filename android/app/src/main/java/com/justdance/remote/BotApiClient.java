package com.justdance.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class BotApiClient {
    private final String baseUrl;
    private final String adminToken;
    private Boolean tokenAllowed;

    BotApiClient(String baseUrl, String adminToken) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    String getBaseUrl() {
        return baseUrl;
    }

    BotState getState() throws Exception {
        return BotState.fromJson(getJson("/api/queue"));
    }

    List<Song> getSongs() throws Exception {
        JSONObject data = getJson("/api/songs");
        JSONArray songs = data.optJSONArray("songs");
        List<Song> result = new ArrayList<>();
        if (songs == null) return result;

        for (int i = 0; i < songs.length(); i++) {
            result.add(Song.fromJson(songs.optJSONObject(i)));
        }
        return result;
    }

    JSONObject requestSong(String song) throws Exception {
        JSONObject body = new JSONObject()
                .put("song", song);
        return postJson("/api/request", body);
    }

    /** Trades a 6-digit pairing code for a token. The saved token, if any, is not sent along. */
    String pairCompanion(String code, String deviceName) throws Exception {
        JSONObject body = new JSONObject().put("code", code).put("device", deviceName);
        JSONObject result = postJson("/api/companion/pair", body, false);
        return result.optString("dashboardToken", "");
    }

    JSONObject skipSong() throws Exception {
        return postJson("/api/skip", new JSONObject());
    }

    JSONObject clearQueue() throws Exception {
        return postJson("/api/clear", new JSONObject());
    }

    JSONObject removeEntry(String id) throws Exception {
        return postJson("/api/remove", new JSONObject().put("id", id));
    }

    JSONObject pickEntry(String id) throws Exception {
        return postJson("/api/pick", new JSONObject().put("id", id));
    }

    JSONObject updateTheme(String theme) throws Exception {
        return postJson("/api/theme", new JSONObject().put("overlayTheme", theme));
    }

    JSONObject setQueueOpen(boolean open) throws Exception {
        return postJson("/api/queue/state", new JSONObject().put("open", open));
    }

    JSONObject updateFilters(List<String> enabledGames) throws Exception {
        JSONArray games = new JSONArray();
        for (String game : enabledGames) games.put(game);
        return postJson("/api/filters", new JSONObject().put("enabledGames", games));
    }

    // Reading the queue and the song list needs no token, so GET requests never carry it.
    private JSONObject getJson(String path) throws Exception {
        HttpURLConnection connection = open(path, false);
        connection.setRequestMethod("GET");
        return readJson(connection);
    }

    private JSONObject postJson(String path, JSONObject body) throws Exception {
        return postJson(path, body, true);
    }

    private JSONObject postJson(String path, JSONObject body, boolean withToken) throws Exception {
        HttpURLConnection connection = open(path, withToken);
        connection.setRequestMethod("POST");
        // A redirect would repeat the request, token included, at whatever address the reply names.
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(body.toString());
        }

        return readJson(connection);
    }

    private HttpURLConnection open(String path, boolean withToken) throws IOException {
        boolean sendToken = withToken && !adminToken.isEmpty();
        if (sendToken && !tokenAllowed()) {
            throw new IOException("The token is only sent over https or to a bot on your own network. Link with Cloud for a website.");
        }
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        if (sendToken) connection.setRequestProperty("X-Queue-Admin", adminToken);
        return connection;
    }

    private synchronized boolean tokenAllowed() {
        if (tokenAllowed == null) tokenAllowed = LinkTarget.mayCarryToken(baseUrl);
        return tokenAllowed;
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        JSONObject data;
        try {
            data = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception error) {
            // A web page instead of JSON: a wrong address, or a channel the site doesn't have.
            throw new IOException("That address did not answer like the bot (HTTP " + status + ").");
        }
        if (status < 200 || status >= 300 || data.optBoolean("ok", true) == false) {
            String message = data.optString("message", "Request failed.");
            throw new IOException(message);
        }
        return data;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static List<String> manualBaseUrlCandidates(String raw) {
        Set<String> candidates = new LinkedHashSet<>();
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return new ArrayList<>();

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }

        try {
            URL url = new URL(value);
            String protocol = url.getProtocol();
            String host = url.getHost();
            int port = url.getPort();

            if (host == null || host.isEmpty()) return new ArrayList<>();

            if (port > 0) {
                candidates.add(protocol + "://" + host + ":" + port);
            } else {
                candidates.add(protocol + "://" + host + ":3000");
                candidates.add(protocol + "://" + host + ":3001");
                candidates.add(protocol + "://" + host);
            }
        } catch (Exception ignored) {
            candidates.add(normalizeBaseUrl(value));
        }

        return new ArrayList<>(candidates);
    }
}
