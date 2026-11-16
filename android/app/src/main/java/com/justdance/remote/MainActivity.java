package com.justdance.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "just_dance_remote";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_TOKEN = "admin_token";
    // The bot the saved token belongs to. The token is never sent to any other address.
    private static final String KEY_TOKEN_URL = "admin_token_url";
    private static final String KEY_MODE = "link_mode";
    private static final String MODE_LAN = "lan";
    private static final String MODE_CLOUD = "cloud";
    private static final int SAFE_TOP_EXTRA_DP = 34;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private SharedPreferences preferences;
    private BotApiClient apiClient;
    private EventStreamClient eventStreamClient;
    private ArrayAdapter<Song> songAdapter;

    private TextView linkStatusView;
    private EditText serverInput;
    private EditText cloudSiteInput;
    private EditText cloudChannelInput;
    private AutoCompleteTextView songInput;
    private TextView messageView;
    private TextView statusView;
    private TextView channelView;
    private TextView queueCountView;
    private TextView songCountView;
    private LinearLayout queueList;
    private LinearLayout historyList;
    private LinearLayout filterList;
    private TextView statusSectionTitle;
    private LinearLayout statusSectionBody;
    private TextView statusSectionChevron;
    private TextView filterSectionChevron;
    private LinearLayout filterSectionBody;
    private TextView queueSectionTitle;
    private Button queueToggleButton;
    private boolean queueOpenState = true;
    private boolean statusAutoCollapsed;

    private final List<Song> songs = new ArrayList<>();
    private final List<CheckBox> filterChecks = new ArrayList<>();
    private volatile int linkAttemptId;
    private String linkedBaseUrl = "";
    private boolean linkedCloud;

    private int background;
    private int surface;
    private int field;
    private int text;
    private int muted;
    private int accent;
    private int accentSoft;
    private int secondarySoft;
    private int outline;
    private int danger;
    private int success;
    private int warning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindSavedToken();
        initColors();
        showLinkPage();
        startAutoLink(false);
    }

    // Tokens saved by older versions weren't tied to a bot. Tie them to the saved link, or drop them.
    private void bindSavedToken() {
        if (preferences.getString(KEY_TOKEN, "").isEmpty() || preferences.contains(KEY_TOKEN_URL)) return;
        String savedUrl = BotApiClient.normalizeBaseUrl(preferences.getString(KEY_BASE_URL, ""));
        if (savedUrl.isEmpty()) {
            preferences.edit().remove(KEY_TOKEN).apply();
        } else {
            preferences.edit().putString(KEY_TOKEN_URL, savedUrl).apply();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (eventStreamClient != null) eventStreamClient.stop();
        io.shutdownNow();
    }

    private void initColors() {
        background = systemColor("system_neutral1_1000", Color.rgb(8, 11, 16));
        surface = systemColor("system_neutral1_900", Color.rgb(21, 26, 33));
        field = systemColor("system_neutral2_800", Color.rgb(31, 38, 47));
        text = systemColor("system_neutral1_50", Color.rgb(243, 247, 250));
        muted = systemColor("system_neutral2_300", Color.rgb(158, 171, 186));
        outline = systemColor("system_neutral2_700", Color.rgb(58, 69, 83));
        accent = systemColor("system_accent1_300", Color.rgb(128, 203, 255));
        accentSoft = systemColor("system_accent1_800", Color.rgb(28, 74, 117));
        secondarySoft = systemColor("system_accent2_800", Color.rgb(61, 62, 104));
        danger = Color.rgb(255, 180, 171);
        success = Color.rgb(134, 239, 172);
        warning = Color.rgb(251, 191, 36);

        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
    }

    private void showLinkPage() {
        if (eventStreamClient != null) eventStreamClient.stop();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        applySafePadding(root, dp(18), dp(SAFE_TOP_EXTRA_DP), dp(28));
        scrollView.addView(root, matchWrap());

        TextView title = textView("Link Remote", 30, Typeface.BOLD, text);
        root.addView(title, matchWrap());

        TextView subtitle = textView("Link to a bot on this Wi-Fi, or to your channel on a hosted site.", 14, Typeface.NORMAL, muted);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle, matchWrap());

        linkStatusView = textView("Searching nearby bot...", 16, Typeface.BOLD, readableOn(secondarySoft));
        linkStatusView.setGravity(Gravity.CENTER_VERTICAL);
        linkStatusView.setMinHeight(dp(52));
        linkStatusView.setPadding(dp(14), 0, dp(14), 0);
        linkStatusView.setBackground(roundRect(secondarySoft, 22, outline, 1));
        root.addView(linkStatusView, matchWrap());

        root.addView(linkSection());
        root.addView(cloudLinkSection());
        setContentView(scrollView);
    }

    private View cloudLinkSection() {
        LinearLayout section = card();
        section.addView(sectionTitle("Cloud"));

        TextView helper = textView("For a hosted bot: enter the website and your Twitch channel, or paste your /c/channel link. https only.", 13, Typeface.NORMAL, muted);
        helper.setPadding(0, 0, 0, dp(8));
        section.addView(helper, matchWrap());

        String[] saved = LinkTarget.cloudParts(isCloudMode() ? preferences.getString(KEY_BASE_URL, "") : "");

        cloudSiteInput = editText("Website, for example jd.example.com");
        cloudSiteInput.setSingleLine(true);
        cloudSiteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cloudSiteInput.setText(saved[0]);
        section.addView(cloudSiteInput, matchWrap());

        cloudChannelInput = editText("Twitch channel");
        cloudChannelInput.setSingleLine(true);
        cloudChannelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cloudChannelInput.setText(saved[1]);
        section.addView(cloudChannelInput, topMargin(matchWrap(), 8));

        Button linkButton = button("Link Cloud", accent);
        linkButton.setOnClickListener(view -> linkCloud());
        section.addView(linkButton, topMargin(matchWrap(), 10));
        return section;
    }

    private View linkSection() {
        LinearLayout section = card();
        section.addView(sectionTitle("Same Wi-Fi"));

        TextView helper = textView("For the desktop bot: tap Scan, or enter the computer IPv4 address or the Phone companion URL. The app tries port 3000 first.", 13, Typeface.NORMAL, muted);
        helper.setPadding(0, 0, 0, dp(8));
        section.addView(helper, matchWrap());

        serverInput = editText("Windows IP, for example 192.168.1.23");
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(isCloudMode() ? "" : preferences.getString(KEY_BASE_URL, ""));
        section.addView(serverInput, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button linkButton = button("Link", accent);
        linkButton.setOnClickListener(view -> linkFromInputs());

        Button scanButton = button("Scan", secondarySoft);
        scanButton.setOnClickListener(view -> startAutoLink(true));

        row.addView(linkButton, weightWrap(1));
        row.addView(space(10), new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(scanButton, weightWrap(1));
        section.addView(row, topMargin(matchWrap(), 10));
        return section;
    }

    private void showRemoteUi(InitialData data, boolean cloud, String notice) {
        linkedBaseUrl = data.client.getBaseUrl();
        linkedCloud = cloud;
        apiClient = data.client;
        statusAutoCollapsed = false;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        applySafePadding(root, dp(18), dp(SAFE_TOP_EXTRA_DP), dp(28));
        scrollView.addView(root, matchWrap());

        TextView title = textView("Just Dance Remote", 30, Typeface.BOLD, text);
        root.addView(title, matchWrap());

        TextView subtitle = textView(cloud ? "Linked to hosted bot" : "Linked to bot", 14, Typeface.NORMAL, muted);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle, matchWrap());

        root.addView(statusSection());
        root.addView(requestSection());
        root.addView(controlSection());
        root.addView(queueSection());
        root.addView(historySection());
        root.addView(filtersSection());

        setContentView(scrollView);

        songs.clear();
        songs.addAll(data.songs);
        songAdapter.notifyDataSetChanged();
        renderState(data.state);
        startEvents(linkedBaseUrl);
        showMessage(notice, false);
    }

    private View statusSection() {
        LinearLayout section = card();

        LinearLayout header = collapsibleHeader("Bot Status");
        statusSectionTitle = (TextView) header.getChildAt(0);
        statusSectionChevron = (TextView) header.getChildAt(1);
        section.addView(header, matchWrap());

        statusSectionBody = new LinearLayout(this);
        statusSectionBody.setOrientation(LinearLayout.VERTICAL);

        statusView = textView("Disconnected", 16, Typeface.BOLD, readableOn(danger));
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setMinHeight(dp(44));
        statusView.setPadding(dp(12), 0, dp(12), 0);
        statusView.setBackground(roundRect(danger, 20));
        statusSectionBody.addView(statusView, topMargin(matchWrap(), 10));

        channelView = textView("Channel: -", 14, Typeface.NORMAL, muted);
        queueCountView = textView("Queue: -", 14, Typeface.NORMAL, muted);
        songCountView = textView("Catalog: -", 14, Typeface.NORMAL, muted);
        statusSectionBody.addView(channelView, topMargin(matchWrap(), 8));
        statusSectionBody.addView(queueCountView, matchWrap());
        statusSectionBody.addView(songCountView, matchWrap());

        TextView linkedView = textView(linkedBaseUrl.isEmpty() ? "Linked: -" : "Linked: " + linkedBaseUrl, 13, Typeface.NORMAL, muted);
        statusSectionBody.addView(linkedView, topMargin(matchWrap(), 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button tokenButton = button("Update Token", accent);
        tokenButton.setOnClickListener(view -> showTokenDialog());

        Button unlinkButton = button("Unlink", danger);
        unlinkButton.setOnClickListener(view -> unlink());

        row.addView(tokenButton, weightWrap(1));
        row.addView(space(10), new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(unlinkButton, weightWrap(1));
        statusSectionBody.addView(row, topMargin(matchWrap(), 10));

        messageView = textView("Linked.", 13, Typeface.NORMAL, muted);
        messageView.setPadding(0, dp(10), 0, 0);
        statusSectionBody.addView(messageView, matchWrap());

        section.addView(statusSectionBody, matchWrap());

        header.setOnClickListener(view -> setStatusCollapsed(statusSectionBody.getVisibility() == View.VISIBLE));
        setStatusCollapsed(false);
        return section;
    }

    private void setStatusCollapsed(boolean collapsed) {
        if (statusSectionBody == null) return;
        statusSectionBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (statusSectionChevron != null) statusSectionChevron.setText(collapsed ? "▸" : "▾");
    }

    private LinearLayout collapsibleHeader(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(40));
        row.setPadding(0, 0, 0, dp(2));
        row.setClickable(true);
        row.setFocusable(true);

        TextView title = textView(label, 17, Typeface.BOLD, text);
        title.setLayoutParams(weightWrap(1));
        row.addView(title);

        TextView chevron = textView("▾", 16, Typeface.BOLD, muted);
        chevron.setPadding(dp(8), 0, dp(4), 0);
        row.addView(chevron);
        return row;
    }

    private View requestSection() {
        LinearLayout section = card();
        section.addView(sectionTitle("Add Request"));

        songInput = new AutoCompleteTextView(this);
        styleEditText(songInput, "Song title or YouTube URL");
        songInput.setSingleLine(true);
        songInput.setThreshold(1);
        songInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        songAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, songs);
        songInput.setAdapter(songAdapter);
        songInput.setOnItemClickListener((parent, view, position, id) -> {
            Song song = (Song) parent.getItemAtPosition(position);
            songInput.setText(song.title, false);
            songInput.setSelection(songInput.length());
        });
        songInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                addRequest();
                return true;
            }
            return false;
        });
        section.addView(songInput, matchWrap());

        Button addButton = button("Add To Queue", accent);
        addButton.setOnClickListener(view -> addRequest());
        section.addView(addButton, topMargin(matchWrap(), 10));
        return section;
    }

    private View controlSection() {
        LinearLayout section = card();
        section.addView(sectionTitle("Controls"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        Button nextButton = button("Next", accent);
        nextButton.setOnClickListener(view -> postAction(() -> apiClient.skipSong()));

        Button clearButton = button("Clear", danger);
        clearButton.setOnClickListener(view -> confirmClear());

        row.addView(nextButton, weightWrap(1));
        row.addView(space(10), new LinearLayout.LayoutParams(dp(10), 1));
        row.addView(clearButton, weightWrap(1));
        section.addView(row, matchWrap());

        queueToggleButton = button("Close Queue", danger);
        queueToggleButton.setOnClickListener(view -> postAction(() -> apiClient.setQueueOpen(!queueOpenState)));
        section.addView(queueToggleButton, topMargin(matchWrap(), 10));
        return section;
    }

    private void applyQueueToggleLabel() {
        if (queueToggleButton == null) return;
        boolean open = queueOpenState;
        queueToggleButton.setText(open ? "Close Queue" : "Open Queue");
        int color = open ? danger : accent;
        queueToggleButton.setBackground(rippleRoundRect(color, 20));
        queueToggleButton.setTextColor(readableOn(color));
    }

    private View queueSection() {
        LinearLayout section = card();
        queueSectionTitle = sectionTitle("Queue");
        section.addView(queueSectionTitle);
        queueList = new LinearLayout(this);
        queueList.setOrientation(LinearLayout.VERTICAL);
        section.addView(queueList, matchWrap());
        renderEmpty(queueList, "No queue entries yet.");
        return section;
    }

    private void applyQueueHeaderLabel() {
        if (queueSectionTitle == null) return;
        queueSectionTitle.setText(queueOpenState ? "Queue (Opened)" : "Queue (Closed)");
    }

    private View historySection() {
        LinearLayout section = card();
        section.addView(sectionTitle("Recently Played"));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        section.addView(historyList, matchWrap());
        renderEmpty(historyList, "Nothing has been played yet.");
        return section;
    }

    private View filtersSection() {
        LinearLayout section = card();

        LinearLayout header = collapsibleHeader("Game Filters");
        filterSectionChevron = (TextView) header.getChildAt(1);
        section.addView(header, matchWrap());

        filterSectionBody = new LinearLayout(this);
        filterSectionBody.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView actionsScroller = new HorizontalScrollView(this);
        actionsScroller.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actionsScroller.addView(actions, matchWrap());

        Button selectAllButton = button("All", field);
        selectAllButton.setOnClickListener(view -> setAllFilters(true));

        Button selectNoneButton = button("None", field);
        selectNoneButton.setOnClickListener(view -> setAllFilters(false));

        Button applyButton = button("Apply", accent);
        applyButton.setOnClickListener(view -> applyFilters());

        actions.addView(selectAllButton, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(space(8), new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(selectNoneButton, new LinearLayout.LayoutParams(dp(96), dp(44)));
        actions.addView(space(8), new LinearLayout.LayoutParams(dp(8), 1));
        actions.addView(applyButton, new LinearLayout.LayoutParams(dp(112), dp(44)));
        filterSectionBody.addView(actionsScroller, topMargin(matchWrap(), 6));

        filterList = new LinearLayout(this);
        filterList.setOrientation(LinearLayout.VERTICAL);
        filterSectionBody.addView(filterList, topMargin(matchWrap(), 8));
        renderEmpty(filterList, "Connect to load filters.");

        section.addView(filterSectionBody, matchWrap());

        header.setOnClickListener(view -> setFiltersCollapsed(filterSectionBody.getVisibility() == View.VISIBLE));
        setFiltersCollapsed(true);
        return section;
    }

    private void setFiltersCollapsed(boolean collapsed) {
        if (filterSectionBody == null) return;
        filterSectionBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (filterSectionChevron != null) filterSectionChevron.setText(collapsed ? "▸" : "▾");
    }

    // Tries the saved link, then (same Wi-Fi mode only) looks for a bot on the network.
    // userScan: the Scan button, which always means "search this Wi-Fi".
    private void startAutoLink(boolean userScan) {
        int attempt = nextLinkAttempt();
        boolean savedCloud = isCloudMode();
        String savedUrl = userScan && savedCloud ? "" : preferences.getString(KEY_BASE_URL, "").trim();

        if (savedCloud && savedUrl.isEmpty() && !userScan) {
            // Last used with a hosted bot: wait for the user instead of probing whatever Wi-Fi this is.
            updateLinkStatus("Enter your website and channel under Cloud, or tap Scan for a bot on this Wi-Fi.", false);
            return;
        }

        updateLinkStatus(savedUrl.isEmpty() ? "Scanning this Wi-Fi..." : "Checking saved link...", false);
        io.execute(() -> {
            if (!savedUrl.isEmpty()) {
                try {
                    InitialData data = loadInitialData(savedUrl);
                    postRemote(attempt, data, savedCloud, savedCloud ? "Linked to saved channel." : "Linked to saved bot.");
                    return;
                } catch (Exception error) {
                    if (savedCloud) {
                        // A hosted bot is never searched for on the local network.
                        postLinkStatus(attempt, "Could not reach the saved site: " + error.getMessage(), true);
                        return;
                    }
                    postLinkStatus(attempt, "Saved link failed. Scanning Wi-Fi...", false);
                }
            }

            String discoveredUrl = new BotDiscoveryClient().findFirst();
            if (discoveredUrl == null) {
                postLinkStatus(attempt, "No bot found. Start the bot with phone companion access on, then scan again.", true);
                return;
            }

            try {
                InitialData data = loadInitialData(discoveredUrl);
                postRemote(attempt, data, false, "Linked automatically to " + data.client.getBaseUrl());
            } catch (Exception error) {
                postLinkStatus(attempt, "Found a bot, but could not load it: " + error.getMessage(), true);
            }
        });
    }

    private void linkFromInputs() {
        List<String> candidates = BotApiClient.manualBaseUrlCandidates(serverInput.getText().toString());

        if (candidates.isEmpty()) {
            startAutoLink(true);
            return;
        }

        int attempt = nextLinkAttempt();
        updateLinkStatus("Trying " + candidates.get(0) + "...", false);
        io.execute(() -> {
            Exception lastError = null;
            for (String candidate : candidates) {
                postLinkStatus(attempt, "Trying " + candidate + "...", false);
                try {
                    InitialData data = loadInitialData(candidate);
                    postRemote(attempt, data, false, "Linked to " + data.client.getBaseUrl());
                    return;
                } catch (Exception error) {
                    lastError = error;
                }
            }

            String detail = lastError == null ? "No response." : lastError.getMessage();
            postLinkStatus(attempt, "Could not link. Check the bot's phone companion URL on port 3000. " + detail, true);
        });
    }

    private void linkCloud() {
        String baseUrl;
        try {
            baseUrl = LinkTarget.cloudBaseUrl(cloudSiteInput.getText().toString(), cloudChannelInput.getText().toString());
        } catch (IllegalArgumentException error) {
            nextLinkAttempt();
            updateLinkStatus(error.getMessage(), true);
            return;
        }

        int attempt = nextLinkAttempt();
        updateLinkStatus("Trying " + baseUrl + "...", false);
        io.execute(() -> {
            try {
                InitialData data = loadInitialData(baseUrl);
                postRemote(attempt, data, true, "Linked to " + data.client.getBaseUrl());
            } catch (Exception error) {
                postLinkStatus(attempt, "Could not link. Check the website and channel. " + error.getMessage(), true);
            }
        });
    }

    private InitialData loadInitialData(String baseUrl) throws Exception {
        BotApiClient client = new BotApiClient(baseUrl, tokenFor(baseUrl));
        BotState state = client.getState();
        List<Song> fetchedSongs = client.getSongs();
        return new InitialData(client, state, fetchedSongs);
    }

    private void postRemote(int attempt, InitialData data, boolean cloud, String notice) {
        mainHandler.post(() -> {
            if (attempt != linkAttemptId) return;
            String baseUrl = data.client.getBaseUrl();
            preferences.edit()
                    .putString(KEY_BASE_URL, baseUrl)
                    .putString(KEY_MODE, cloud ? MODE_CLOUD : MODE_LAN)
                    .apply();
            boolean paired = !tokenFor(baseUrl).isEmpty();
            showRemoteUi(data, cloud, paired ? notice : notice + " Not paired yet: tap Update Token.");
        });
    }

    private void postLinkStatus(int attempt, String message, boolean isError) {
        mainHandler.post(() -> {
            if (attempt == linkAttemptId) updateLinkStatus(message, isError);
        });
    }

    private int nextLinkAttempt() {
        return ++linkAttemptId;
    }

    private boolean isCloudMode() {
        return MODE_CLOUD.equals(preferences.getString(KEY_MODE, MODE_LAN));
    }

    // The saved token, but only for the bot it was paired with. Any other address gets none.
    private String tokenFor(String baseUrl) {
        String boundUrl = preferences.getString(KEY_TOKEN_URL, "");
        String target = BotApiClient.normalizeBaseUrl(baseUrl);
        if (target.isEmpty() || !target.equals(boundUrl)) return "";
        return preferences.getString(KEY_TOKEN, "").trim();
    }

    private String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String name = model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT)) ? model : (maker + " " + model).trim();
        return name.isEmpty() ? "Android phone" : name;
    }

    private void showTokenDialog() {
        EditText input = editText("Pairing code or dashboard token");
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(input, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle("Update token")
                .setMessage(linkedCloud
                        ? "Open your dashboard on the website, click Phone, then enter the 6-digit code here."
                        : "Use Pair Phone in the desktop app, then enter the 6-digit code here. Pasting a dashboard token still works.")
                .setView(content)
                .setPositiveButton("Update Token", (dialog, which) -> pairOrUpdateToken(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pairOrUpdateToken(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 6 && value.length() <= 12 && apiClient != null) {
            pairWithCode(digits);
            return;
        }
        updateToken(value);
    }

    private void pairWithCode(String code) {
        showMessage("Pairing phone...", false);
        BotApiClient client = apiClient;
        String pairedUrl = linkedBaseUrl;
        io.execute(() -> {
            try {
                String token = client.pairCompanion(code, deviceName());
                if (token.isEmpty()) throw new Exception("Pairing succeeded, but no token was returned.");
                mainHandler.post(() -> {
                    // The token belongs to the bot that issued it, not to whatever is linked by now.
                    if (pairedUrl.equals(linkedBaseUrl)) updateToken(token);
                });
            } catch (Exception error) {
                mainHandler.post(() -> showMessage(error.getMessage(), true));
            }
        });
    }

    private void updateToken(String token) {
        if (linkedBaseUrl.isEmpty()) return;
        preferences.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_TOKEN_URL, linkedBaseUrl)
                .apply();
        apiClient = new BotApiClient(linkedBaseUrl, token);
        showMessage(token.isEmpty() ? "Token cleared." : "Token updated.", false);
    }

    private void unlink() {
        linkAttemptId++;
        linkedBaseUrl = "";
        apiClient = null;
        if (eventStreamClient != null) eventStreamClient.stop();
        // Unlinking forgets the bot and its token; linking again means pairing again.
        preferences.edit().remove(KEY_BASE_URL).remove(KEY_TOKEN).remove(KEY_TOKEN_URL).apply();
        showLinkPage();
    }

    private void startEvents(String baseUrl) {
        if (eventStreamClient != null) eventStreamClient.stop();
        eventStreamClient = new EventStreamClient(baseUrl, new EventStreamClient.Listener() {
            @Override
            public void onEvent(String data) {
                try {
                    BotState state = BotState.fromJson(new JSONObject(data));
                    mainHandler.post(() -> renderState(state));
                } catch (Exception error) {
                    mainHandler.post(() -> showMessage("Event parse error: " + error.getMessage(), true));
                }
            }

            @Override
            public void onError(Exception error) {
                mainHandler.post(() -> showMessage("Event stream reconnecting: " + error.getMessage(), true));
            }
        });
        eventStreamClient.start();
    }

    private void addRequest() {
        if (apiClient == null) {
            showMessage("Connect to the bot first.", true);
            return;
        }

        String song = songInput.getText().toString().trim();
        if (song.isEmpty()) {
            showMessage("Enter a song request.", true);
            return;
        }

        showMessage("Adding request...", false);

        io.execute(() -> {
            try {
                JSONObject result = apiClient.requestSong(song);
                mainHandler.post(() -> {
                    songInput.setText("");
                    showResult(result);
                });
            } catch (Exception error) {
                mainHandler.post(() -> showMessage(error.getMessage(), true));
            }
        });
    }

    private void postAction(ApiAction action) {
        if (apiClient == null) {
            showMessage("Connect to the bot first.", true);
            return;
        }

        showMessage("Sending...", false);
        io.execute(() -> {
            try {
                JSONObject result = action.run();
                mainHandler.post(() -> showResult(result));
            } catch (Exception error) {
                mainHandler.post(() -> showMessage(error.getMessage(), true));
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear queue?")
                .setMessage("This removes every waiting request.")
                .setPositiveButton("Clear", (dialog, which) -> postAction(() -> apiClient.clearQueue()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyFilters() {
        List<String> enabled = new ArrayList<>();
        for (CheckBox checkBox : filterChecks) {
            if (checkBox.isChecked()) {
                enabled.add((String) checkBox.getTag());
            }
        }
        if (enabled.isEmpty()) {
            showMessage("Choose at least one game catalog.", true);
            return;
        }
        postAction(() -> apiClient.updateFilters(enabled));
    }

    private void setAllFilters(boolean checked) {
        for (CheckBox checkBox : filterChecks) checkBox.setChecked(checked);
    }

    private void showResult(JSONObject result) {
        JSONObject state = result.optJSONObject("state");
        if (state != null) renderState(BotState.fromJson(state));
        showMessage(result.optString("message", "Done."), false);
    }

    private void renderState(BotState state) {
        int statusColor = state.botConnected ? success : warning;
        statusView.setText(state.botConnected ? "Connected to Twitch" : "Bot server online, Twitch offline");
        statusView.setTextColor(readableOn(statusColor));
        statusView.setBackground(roundRect(statusColor, 20));
        channelView.setText(state.channel.isEmpty() ? "Channel: -" : "Channel: #" + state.channel);
        queueCountView.setText("Queue: " + state.queue.size() + " / " + state.maxQueueSize);
        songCountView.setText("Catalog: " + state.totalSongs + " requestable songs");
        renderQueue(state.queue);
        renderHistory(state.history);
        renderFilters(state.availableGames, new HashSet<>(state.enabledGames));

        boolean linked = !linkedBaseUrl.isEmpty();
        if (statusSectionTitle != null) {
            String suffix;
            if (!linked) suffix = "";
            else if (state.botConnected) suffix = " — Connected";
            else suffix = " — Bot online, Twitch offline";
            statusSectionTitle.setText("Bot Status" + suffix);
        }
        if (linked && state.botConnected && !statusAutoCollapsed) {
            statusAutoCollapsed = true;
            setStatusCollapsed(true);
        }

        queueOpenState = state.queueOpen;
        applyQueueHeaderLabel();
        applyQueueToggleLabel();
    }

    private void renderQueue(List<QueueEntry> queue) {
        queueList.removeAllViews();
        if (queue.isEmpty()) {
            renderEmpty(queueList, "No queue entries yet.");
            return;
        }

        for (int i = 0; i < queue.size(); i++) {
            queueList.addView(queueRow(queue.get(i), i), topMargin(matchWrap(), i == 0 ? 0 : 8));
        }
    }

    private View queueRow(QueueEntry entry, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundRect(field, 20, outline, 1));

        TextView title = textView((index + 1) + ". " + entry.song.title, 16, Typeface.BOLD, text);
        row.addView(title, matchWrap());

        TextView detail = textView(entry.song.artist + " - " + entry.song.game + " - @" + entry.user, 13, Typeface.NORMAL, muted);
        row.addView(detail, topMargin(matchWrap(), 3));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button pickButton = button("Pick", accent);
        pickButton.setOnClickListener(view -> postAction(() -> apiClient.pickEntry(entry.id)));

        Button removeButton = button("Remove", danger);
        removeButton.setOnClickListener(view -> postAction(() -> apiClient.removeEntry(entry.id)));

        buttons.addView(pickButton, weightWrap(1));
        buttons.addView(space(8), new LinearLayout.LayoutParams(dp(8), 1));
        buttons.addView(removeButton, weightWrap(1));
        row.addView(buttons, topMargin(matchWrap(), 10));
        return row;
    }

    private void renderHistory(List<QueueEntry> history) {
        historyList.removeAllViews();
        if (history.isEmpty()) {
            renderEmpty(historyList, "Nothing has been played yet.");
            return;
        }

        for (int i = 0; i < history.size(); i++) {
            QueueEntry entry = history.get(i);
            TextView item = textView((i + 1) + ". " + entry.song.title + " - " + entry.song.artist + " - @" + entry.user, 14, Typeface.NORMAL, text);
            item.setPadding(0, dp(6), 0, dp(6));
            historyList.addView(item, matchWrap());
        }
    }

    private void renderFilters(List<GameOption> games, Set<String> enabled) {
        filterList.removeAllViews();
        filterChecks.clear();

        if (games.isEmpty()) {
            renderEmpty(filterList, "Connect to load filters.");
            return;
        }

        for (GameOption game : games) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(game.displayLabel());
            checkBox.setTextSize(14);
            checkBox.setMinHeight(dp(48));
            checkBox.setPadding(dp(12), 0, dp(12), 0);
            checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
            checkBox.setTextColor(chipTextColors());
            checkBox.setBackground(chipBackground());
            checkBox.setTag(game.key);
            checkBox.setChecked(enabled.contains(game.key));
            filterChecks.add(checkBox);
            filterList.addView(checkBox, topMargin(matchWrap(), 6));
        }
    }

    private void renderEmpty(LinearLayout target, String label) {
        TextView empty = textView(label, 14, Typeface.NORMAL, muted);
        empty.setPadding(0, dp(8), 0, dp(8));
        target.addView(empty, matchWrap());
    }

    private void updateLinkStatus(String message, boolean isError) {
        if (linkStatusView == null) return;
        int color = isError ? danger : secondarySoft;
        linkStatusView.setText(message == null || message.isEmpty() ? "Searching nearby bot..." : message);
        linkStatusView.setTextColor(readableOn(color));
        linkStatusView.setBackground(roundRect(color, 22, outline, 1));
    }

    private void showMessage(String message, boolean isError) {
        if (messageView == null) return;
        messageView.setText(message == null || message.isEmpty() ? "Done." : message);
        messageView.setTextColor(isError ? Color.rgb(255, 146, 146) : muted);
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(roundRect(surface, 24, outline, 1));
        view.setElevation(dp(1));
        view.setLayoutParams(topMargin(matchWrap(), 12));
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = textView(value, 17, Typeface.BOLD, text);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView textView(String value, int sp, int style, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private EditText editText(String hint) {
        EditText editText = new EditText(this);
        styleEditText(editText, hint);
        return editText;
    }

    private void styleEditText(EditText editText, String hint) {
        editText.setHint(hint);
        editText.setHintTextColor(Color.rgb(112, 126, 142));
        editText.setTextColor(text);
        editText.setTextSize(15);
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setMinHeight(dp(52));
        editText.setSingleLine(false);
        editText.setBackground(roundRect(field, 18, outline, 1));
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(readableOn(color));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rippleRoundRect(color, 20));
        button.setElevation(dp(1));
        return button;
    }

    private Space space(int sizeDp) {
        Space space = new Space(this);
        space.setMinimumWidth(dp(sizeDp));
        return space;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        return roundRect(color, radiusDp, Color.TRANSPARENT, 0);
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private Drawable rippleRoundRect(int color, int radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(withAlpha(readableOn(color), 42)),
                roundRect(color, radiusDp),
                null
        );
    }

    private Drawable chipBackground() {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_checked}, roundRect(field, 0, accent, 2));
        drawable.addState(new int[]{}, roundRect(field, 0, outline, 1));
        return drawable;
    }

    private ColorStateList chipTextColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        accent,
                        text
                }
        );
    }

    private int systemColor(String name, int fallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return fallback;
        int id = getResources().getIdentifier(name, "color", "android");
        return id == 0 ? fallback : getColor(id);
    }

    private int readableOn(int color) {
        return luminance(color) > 0.58 ? Color.rgb(20, 24, 31) : Color.WHITE;
    }

    private double luminance(int color) {
        return 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void applySafePadding(View view, int side, int topExtra, int bottomExtra) {
        int fallbackTop = topExtra + systemDimension("status_bar_height");
        int fallbackBottom = bottomExtra + systemDimension("navigation_bar_height");
        view.setPadding(side, fallbackTop, side, fallbackBottom);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setOnApplyWindowInsetsListener((target, insets) -> {
                int top = Math.max(insets.getSystemWindowInsetTop(), safeCutoutTop(insets));
                int bottom = insets.getSystemWindowInsetBottom();
                target.setPadding(side, topExtra + top, side, bottomExtra + bottom);
                return insets;
            });
        }
    }

    private int safeCutoutTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || insets.getDisplayCutout() == null) return 0;
        return insets.getDisplayCutout().getSafeInsetTop();
    }

    private int systemDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int dp) {
        params.topMargin = dp(dp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    interface ApiAction {
        JSONObject run() throws Exception;
    }

    private static final class InitialData {
        final BotApiClient client;
        final BotState state;
        final List<Song> songs;

        InitialData(BotApiClient client, BotState state, List<Song> songs) {
            this.client = client;
            this.state = state;
            this.songs = songs;
        }
    }
}
