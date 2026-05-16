package com.justdance.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BotState {
    final List<QueueEntry> queue;
    final List<QueueEntry> history;
    final List<GameOption> availableGames;
    final List<String> enabledGames;
    final int totalSongs;
    final int maxQueueSize;
    final String overlayTheme;
    final String channel;
    final boolean botConnected;
    final boolean queueOpen;

    private BotState(
            List<QueueEntry> queue,
            List<QueueEntry> history,
            List<GameOption> availableGames,
            List<String> enabledGames,
            int totalSongs,
            int maxQueueSize,
            String overlayTheme,
            String channel,
            boolean botConnected,
            boolean queueOpen
    ) {
        this.queue = queue;
        this.history = history;
        this.availableGames = availableGames;
        this.enabledGames = enabledGames;
        this.totalSongs = totalSongs;
        this.maxQueueSize = maxQueueSize;
        this.overlayTheme = overlayTheme;
        this.channel = channel;
        this.botConnected = botConnected;
        this.queueOpen = queueOpen;
    }

    static BotState fromJson(JSONObject json) {
        if (json == null) json = new JSONObject();

        return new BotState(
                readEntries(json.optJSONArray("queue")),
                readEntries(json.optJSONArray("history")),
                readGames(json.optJSONArray("availableGames")),
                readStrings(json.optJSONArray("enabledGames")),
                json.optInt("totalSongs", 0),
                json.optInt("maxQueueSize", 0),
                json.optString("overlayTheme", "dark"),
                json.optString("channel", ""),
                json.optBoolean("botConnected", false),
                json.optBoolean("queueOpen", true)
        );
    }

    private static List<QueueEntry> readEntries(JSONArray array) {
        List<QueueEntry> entries = new ArrayList<>();
        if (array == null) return entries;

        for (int i = 0; i < array.length(); i++) {
            entries.add(QueueEntry.fromJson(array.optJSONObject(i)));
        }
        return entries;
    }

    private static List<GameOption> readGames(JSONArray array) {
        List<GameOption> games = new ArrayList<>();
        if (array == null) return games;

        for (int i = 0; i < array.length(); i++) {
            games.add(GameOption.fromJson(array.optJSONObject(i)));
        }
        return games;
    }

    private static List<String> readStrings(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;

        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }
}

final class QueueEntry {
    final String id;
    final String user;
    final Song song;

    private QueueEntry(String id, String user, Song song) {
        this.id = id;
        this.user = user;
        this.song = song;
    }

    static QueueEntry fromJson(JSONObject json) {
        if (json == null) json = new JSONObject();
        return new QueueEntry(
                json.optString("id", ""),
                json.optString("user", "viewer"),
                Song.fromJson(json.optJSONObject("song"))
        );
    }
}

final class Song {
    final String id;
    final String title;
    final String artist;
    final String game;

    private Song(String id, String title, String artist, String game) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.game = game;
    }

    static Song fromJson(JSONObject json) {
        if (json == null) json = new JSONObject();
        return new Song(
                json.optString("id", ""),
                json.optString("title", "Untitled"),
                json.optString("artist", "Unknown artist"),
                json.optString("game", "")
        );
    }

    @Override
    public String toString() {
        String suffix = game == null || game.isEmpty() ? "" : " - " + game;
        return title + " - " + artist + suffix;
    }
}

final class GameOption {
    final String key;
    final String label;
    final Integer count;

    private GameOption(String key, String label, Integer count) {
        this.key = key;
        this.label = label;
        this.count = count;
    }

    static GameOption fromJson(JSONObject json) {
        if (json == null) json = new JSONObject();
        Integer count = json.isNull("count") ? null : json.optInt("count");
        return new GameOption(
                json.optString("key", ""),
                json.optString("label", ""),
                count
        );
    }

    String displayLabel() {
        return count == null ? label + " (any song)" : label + " (" + count + ")";
    }
}

