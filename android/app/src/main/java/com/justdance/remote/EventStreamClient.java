package com.justdance.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class EventStreamClient {
    interface Listener {
        void onEvent(String data);
        void onError(Exception error);
    }

    private final String baseUrl;
    private final Listener listener;
    private volatile boolean running;
    private volatile HttpURLConnection activeConnection;
    private Thread thread;

    EventStreamClient(String baseUrl, Listener listener) {
        this.baseUrl = BotApiClient.normalizeBaseUrl(baseUrl);
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::runLoop, "bot-events");
        thread.start();
    }

    void stop() {
        running = false;
        if (activeConnection != null) activeConnection.disconnect();
        if (thread != null) thread.interrupt();
    }

    private void runLoop() {
        while (running) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(baseUrl + "/events");
                connection = (HttpURLConnection) url.openConnection();
                activeConnection = connection;
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(0);
                connection.setRequestProperty("Accept", "text/event-stream");

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Event stream returned HTTP " + status);
                }

                readEvents(connection);
            } catch (Exception error) {
                if (running) {
                    listener.onError(error);
                    sleepBeforeRetry();
                }
            } finally {
                if (connection != null) connection.disconnect();
                if (activeConnection == connection) activeConnection = null;
            }
        }
    }

    private void readEvents(HttpURLConnection connection) throws Exception {
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        listener.onEvent(data.toString());
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
