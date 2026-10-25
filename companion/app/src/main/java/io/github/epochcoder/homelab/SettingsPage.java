package io.github.epochcoder.homelab;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every setting in config.json, edited with the remote: the sections down the left, each with a dot
 * that turns green once it is set up, and the chosen section's rows on the right. Moving along the
 * sections shows them; Right goes into the rows and Left comes back.
 *
 * <p>OK on a row edits it: a dialog for text and numbers, a flip for switches, and for lists that
 * live on a server (Immich albums and tags, *arr quality profiles and root folders) a choice from
 * what the server returns. Each server has a Test row. Every change is saved at once, and {@link
 * MainActivity} rebuilds its clients, so the other tabs use it straight away and the screensaver
 * the next time it starts.
 *
 * <p>The only setting not editable here is the extra CA certificate: it cannot sensibly be typed
 * with a remote, so it comes with companion/push-config.sh --ca, into its own file, and this page
 * leaves it alone.
 */
final class SettingsPage extends Page {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinearLayout nav;
    private final LinearLayout pane;
    private final ScrollView paneScroll;
    private final List<Section> sections = new ArrayList<>();
    private final List<View> navRows = new ArrayList<>();

    /** The whole file, edited in place, so keys this page does not show survive a save. */
    private JSONObject json;

    private int shown = -1;

    private static final class Section {
        final int title;
        final int icon;
        final int about;
        final Runnable build;

        Section(int title, int icon, int about, Runnable build) {
            this.title = title;
            this.icon = icon;
            this.about = about;
            this.build = build;
        }
    }

    SettingsPage(MainActivity host) {
        super(host, R.layout.page_settings);
        nav = view.findViewById(R.id.nav);
        pane = view.findViewById(R.id.pane);
        paneScroll = view.findViewById(R.id.pane_scroll);
        json = Config.loadJson(host);

        sections.add(
                new Section(
                        R.string.dream_name,
                        R.drawable.ic_photo,
                        R.string.s_photos_about,
                        this::immich));
        sections.add(
                new Section(
                        R.string.tab_dashboard,
                        R.drawable.ic_tab_dashboard,
                        R.string.s_dashboard_about,
                        this::homepage));
        sections.add(
                new Section(
                        R.string.radarr,
                        R.drawable.ic_movie,
                        R.string.s_radarr_about,
                        () -> arr("radarr", Arr.Kind.MOVIE)));
        sections.add(
                new Section(
                        R.string.sonarr,
                        R.drawable.ic_tv,
                        R.string.s_sonarr_about,
                        () -> arr("sonarr", Arr.Kind.SERIES)));
        sections.add(
                new Section(
                        R.string.qbittorrent,
                        R.drawable.ic_tab_downloads,
                        R.string.s_qbit_about,
                        this::qbittorrent));
        sections.add(
                new Section(R.string.s_certificates, R.drawable.ic_lock, 0, this::certificates));

        LayoutInflater inflater = LayoutInflater.from(host);
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            View row = inflater.inflate(R.layout.item_settings_nav, nav, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(s.icon);
            ((TextView) row.findViewById(R.id.label)).setText(s.title);
            int index = i;
            row.setOnFocusChangeListener(
                    (v, has) -> {
                        if (has) {
                            show(index);
                        }
                    });
            row.setOnClickListener(v -> focusPane());
            nav.addView(row);
            navRows.add(row);
        }
        TextView tip = text(getString(R.string.s_tip), 14, R.color.text_dim);
        tip.setPadding(dp(16), dp(20), dp(12), 0);
        tip.setLineSpacing(0, 1.2f);
        nav.addView(tip);
        refreshDots();
        show(0);
    }

    @Override
    void onShow() {
        // push-config.sh may have written the file meanwhile.
        String disk = Config.loadJson(host).toString();
        if (!disk.equals(json.toString())) {
            onConfigChanged();
        }
    }

    @Override
    void onConfigChanged() {
        json = Config.loadJson(host);
        refreshDots();
        int keep = Math.max(0, shown);
        shown = -1;
        show(keep);
    }

    @Override
    void onDestroy() {
        io.shutdownNow();
    }

    @Override
    View focusTarget() {
        return navRows.get(Math.max(0, shown));
    }

    @Override
    boolean onKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        View focused = host.getCurrentFocus();
        if (focused == null) {
            return false;
        }
        boolean inPane = isInside(focused, pane);
        boolean inNav = isInside(focused, nav);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Straight back to the section shown, not whichever section happens to sit level
                // with the row.
                if (inPane) {
                    navRows.get(shown).requestFocus();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (inNav) {
                    focusPane();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (inPane
                        && FocusFinder.getInstance().findNextFocus(pane, focused, View.FOCUS_UP)
                                == null) {
                    paneScroll.smoothScrollTo(0, 0);
                    host.focusTab();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // The last row or section stays put rather than wandering across to the other
                // column.
                return (inPane
                                && FocusFinder.getInstance()
                                                .findNextFocus(pane, focused, View.FOCUS_DOWN)
                                        == null)
                        || (inNav && focused == navRows.get(navRows.size() - 1));
            default:
                return false;
        }
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == parent) {
                return true;
            }
        }
        return false;
    }

    private void focusPane() {
        for (int i = 0; i < pane.getChildCount(); i++) {
            View c = pane.getChildAt(i);
            if (c.isFocusable() && c.getVisibility() == View.VISIBLE) {
                c.requestFocus();
                return;
            }
        }
    }

    private void show(int index) {
        if (index == shown) {
            return;
        }
        shown = index;
        for (int i = 0; i < navRows.size(); i++) {
            navRows.get(i).setSelected(i == index);
        }
        Section s = sections.get(index);
        pane.removeAllViews();
        paneScroll.scrollTo(0, 0);
        TextView title = text(host.getString(s.title), 32, R.color.text);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        pane.addView(title);
        if (s.about != 0) {
            TextView about = text(host.getString(s.about), 16, R.color.text_dim);
            about.setPadding(0, dp(4), 0, 0);
            pane.addView(about);
        }
        s.build.run();
    }

    private void refreshDots() {
        boolean[] set = {
            new Config.Immich(section("immich")).configured(),
            new Config.Homepage(section("homepage")).configured(),
            new Config.Arr(section("radarr")).configured(),
            new Config.Arr(section("sonarr")).configured(),
            new Config.QBittorrent(section("qbittorrent")).configured(),
            !host.config.caPem.isEmpty(),
        };
        for (int i = 0; i < navRows.size(); i++) {
            ImageView dot = navRows.get(i).findViewById(R.id.dot);
            dot.setColorFilter(host.getColor(set[i] ? R.color.ok : R.color.stroke));
            dot.setContentDescription(
                    getString(set[i] ? R.string.s_status_set : R.string.s_status_unset));
        }
    }

    // Sections, one per service.

    private void immich() {
        JSONObject im = section("immich");
        group(R.string.s_group_connection);
        text(im, "url", R.string.s_url, InputType.TYPE_TEXT_VARIATION_URI, false);
        text(
                im,
                "api_key",
                R.string.s_api_key,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                true);
        note(getString(R.string.s_immich_key_help, String.join(", ", Immich.PERMISSIONS)));
        test(
                () -> {
                    Immich client = immich(im);
                    List<String> missing = client.missingPermissions();
                    if (!missing.isEmpty()) {
                        throw new IOException(
                                getString(
                                        R.string.s_missing_permissions,
                                        String.join(", ", missing)));
                    }
                    int n = client.albums().length();
                    return host.getResources()
                            .getQuantityString(R.plurals.s_connected_albums, n, n);
                });
        group(R.string.s_group_slideshow);
        number(im, "interval_seconds", R.string.s_interval, 20);
        toggle(im, "shuffle", R.string.s_shuffle, true);
        toggle(im, "motion", R.string.s_motion, true);
        toggle(im, "pair_portraits", R.string.s_pair, true);
        toggle(im, "crop", R.string.s_crop, false);
        toggle(im, "show_clock", R.string.s_clock, true);
        toggle(im, "details", R.string.s_details, true);
        group(R.string.s_group_filters);
        number(im, "recent_days", R.string.s_recent_days, 90);
        number(im, "max_photos", R.string.s_max_photos, 500);
        pickMany(
                im,
                "include_albums",
                "Include albums",
                () -> {
                    try {
                        return names(immich(im).albums(), "albumName");
                    } catch (IOException e) {
                        return Collections.emptyList();
                    }
                });
        pickMany(
                im,
                "exclude_albums",
                R.string.s_exclude_albums,
                () -> {
                    try {
                        return names(immich(im).albums(), "albumName");
                    } catch (IOException e) {
                        return Collections.emptyList();
                    }
                });
        pickMany(
                im,
                "exclude_tags",
                R.string.s_exclude_tags,
                () -> names(immich(im).tags(), "value"));
        group(R.string.s_group_screensaver);
        action(
                R.string.s_preview,
                0,
                () -> host.startActivity(new Intent(host, SlideshowActivity.class)));
        screensaver();
    }

    /**
     * "Use as the screensaver": with WRITE_SECURE_SETTINGS (build-and-install.sh grants it over
     * ADB) it selects PhotoDream itself; without, it opens the TV's screensaver settings to pick it
     * there.
     */
    private void screensaver() {
        Row r = row(R.string.s_use_screensaver);
        r.value.setVisibility(View.GONE);
        Runnable bind =
                () -> {
                    if (Screensaver.isOurs(host)) {
                        r.summary(
                                getString(R.string.s_screensaver_is_ours),
                                host.getColor(R.color.ok));
                    } else if (Screensaver.canSet(host)) {
                        r.summary(
                                getString(R.string.s_screensaver_set_summary),
                                host.getColor(R.color.text_dim));
                    } else {
                        r.summary(
                                getString(R.string.s_screensaver_settings_summary),
                                host.getColor(R.color.text_dim));
                    }
                };
        bind.run();
        r.view.setOnClickListener(
                v -> {
                    if (Screensaver.canSet(host)) {
                        Screensaver.select(host);
                        bind.run();
                        return;
                    }
                    Intent dreams = new Intent(Settings.ACTION_DREAM_SETTINGS);
                    if (dreams.resolveActivity(host.getPackageManager()) != null) {
                        host.startActivity(dreams);
                    }
                });
    }

    private void homepage() {
        JSONObject hp = section("homepage");
        group(R.string.s_group_connection);
        text(hp, "url", R.string.s_url, InputType.TYPE_TEXT_VARIATION_URI, false);
        group(R.string.s_group_display);
        number(hp, "text_zoom", R.string.s_text_zoom, 100);
        toggle(hp, "keep_awake", R.string.s_keep_awake, false);
    }

    private void arr(String name, Arr.Kind kind) {
        JSONObject s = section(name);
        group(R.string.s_group_connection);
        text(s, "url", R.string.s_url, InputType.TYPE_TEXT_VARIATION_URI, false);
        text(
                s,
                "api_key",
                R.string.s_api_key,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                true);
        test(() -> getString(R.string.s_connected_to, arr(kind, s).version()));
        group(R.string.s_group_adding);
        pickOne(
                s,
                "quality_profile",
                R.string.s_quality_profile,
                () -> names(arr(kind, s).qualityProfiles(), "name"));
        pickOne(
                s,
                "root_folder",
                R.string.s_root_folder,
                () -> names(arr(kind, s).rootFolders(), "path"));
        if (kind == Arr.Kind.SERIES) {
            String[] labels = new String[Arr.SERIES_MONITOR.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = DetailActivity.monitorLabel(host, Arr.SERIES_MONITOR[i]);
            }
            choice(s, "monitor", R.string.s_monitor, Arr.SERIES_MONITOR, labels);
        }
    }

    private void qbittorrent() {
        JSONObject qb = section("qbittorrent");
        group(R.string.s_group_connection);
        text(qb, "url", R.string.s_url, InputType.TYPE_TEXT_VARIATION_URI, false);
        text(
                qb,
                "username",
                R.string.s_username,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                false);
        text(qb, "password", R.string.s_password, InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        test(
                () -> {
                    Config.QBittorrent c = new Config.QBittorrent(qb);
                    need(c.configured(), R.string.s_fill_in_url);
                    return getString(R.string.s_connected_to, new Qbit(c, host.tls).version());
                });
        note(getString(R.string.s_qbit_note));
    }

    private void certificates() {
        note(getString(host.config.caPem.isEmpty() ? R.string.s_ca_none : R.string.s_ca_set));
    }

    // Saving.

    private void save() {
        try {
            Config.save(host, json);
            host.configSaved(this);
            refreshDots();
        } catch (IOException e) {
            Toast.makeText(
                            host,
                            getString(
                                    R.string.error,
                                    getString(R.string.settings_name),
                                    e.getMessage()),
                            Toast.LENGTH_LONG)
                    .show();
        }
    }

    private JSONObject section(String name) {
        JSONObject s = json.optJSONObject(name);
        if (s == null) {
            s = new JSONObject();
            put(json, name, s);
        }
        return s;
    }

    // Rows. Each is a card: its name on the left, its value, switch or chevron on the right; OK
    // acts on it.

    private static final class Row {
        final View view;
        final TextView title;
        final TextView summary;
        final TextView value;

        Row(View view) {
            this.view = view;
            title = view.findViewById(R.id.title);
            summary = view.findViewById(R.id.summary);
            value = view.findViewById(R.id.value);
        }

        void summary(String text, int color) {
            summary.setText(text);
            summary.setTextColor(color);
            summary.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private Row row(int title) {
        View v = LayoutInflater.from(host).inflate(R.layout.item_setting, pane, false);
        Row r = new Row(v);
        r.title.setText(title);
        pane.addView(v);
        return r;
    }

    private Row row(String title) {
        View v = LayoutInflater.from(host).inflate(R.layout.item_setting, pane, false);
        Row r = new Row(v);
        r.title.setText(title);
        pane.addView(v);
        return r;
    }

    private void text(JSONObject s, String key, int label, int variation, boolean secret) {
        Row r = row(label);
        Runnable bind =
                () -> {
                    String v = s.optString(key);
                    r.value.setText(
                            v.isEmpty() ? getString(R.string.s_not_set) : secret ? mask(v) : v);
                };
        bind.run();
        r.view.setOnClickListener(
                v ->
                        edit(
                                label,
                                s.optString(key),
                                InputType.TYPE_CLASS_TEXT | variation,
                                typed -> {
                                    if (typed.isEmpty()) {
                                        s.remove(key);
                                    } else {
                                        put(s, key, typed);
                                    }
                                    save();
                                    bind.run();
                                }));
    }

    /** Empty means the default, which the value shows. */
    private void number(JSONObject s, String key, int label, int def) {
        Row r = row(label);
        Runnable bind =
                () ->
                        r.value.setText(
                                s.has(key)
                                        ? String.valueOf(s.optInt(key))
                                        : getString(R.string.s_default_value, String.valueOf(def)));
        bind.run();
        r.view.setOnClickListener(
                v ->
                        edit(
                                label,
                                s.has(key) ? String.valueOf(s.optInt(key)) : "",
                                InputType.TYPE_CLASS_NUMBER,
                                typed -> {
                                    try {
                                        put(s, key, Integer.parseInt(typed));
                                    } catch (NumberFormatException e) {
                                        s.remove(key);
                                    }
                                    save();
                                    bind.run();
                                }));
    }

    private void toggle(JSONObject s, String key, int label, boolean def) {
        Row r = row(label);
        r.value.setVisibility(View.GONE);
        Switch sw = r.view.findViewById(R.id.toggle);
        sw.setVisibility(View.VISIBLE);
        sw.setChecked(s.optBoolean(key, def));
        r.view.setOnClickListener(
                v -> {
                    boolean on = !s.optBoolean(key, def);
                    put(s, key, on);
                    sw.setChecked(on);
                    save();
                });
    }

    /** A dialog with one field, the keyboard up; Done or Save keeps it, Clear empties it. */
    private void edit(int label, String current, int inputType, Consumer<String> done) {
        EditText e = new EditText(host);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setText(current);
        e.setSelection(e.length());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setTextColor(host.getColor(R.color.text));
        e.setBackgroundResource(R.drawable.field);
        e.setPadding(dp(18), dp(14), dp(18), dp(14));
        e.setMinWidth(dp(560));
        FrameLayout wrap = new FrameLayout(host);
        wrap.setPadding(dp(24), dp(12), dp(24), dp(4));
        wrap.addView(e);
        AlertDialog d =
                new AlertDialog.Builder(host)
                        .setTitle(label)
                        .setView(wrap)
                        .setPositiveButton(
                                R.string.s_save_field,
                                (dlg, w) -> done.accept(e.getText().toString().trim()))
                        .setNeutralButton(R.string.s_clear, (dlg, w) -> done.accept(""))
                        .setNegativeButton(R.string.cancel, null)
                        .create();
        e.setOnEditorActionListener(
                (v, action, event) -> {
                    if (action != EditorInfo.IME_ACTION_DONE) {
                        return false;
                    }
                    done.accept(e.getText().toString().trim());
                    d.dismiss();
                    return true;
                });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        e.requestFocus();
    }

    private interface Fetch {
        List<String> names() throws Exception;
    }

    /** The current choice as the value; OK loads the server's list and offers it as checkboxes. */
    private void pickMany(JSONObject s, String key, int label, Fetch fetch) {
        pickMany(s, key, host.getString(label), fetch);
    }

    private void pickMany(JSONObject s, String key, String label, Fetch fetch) {
        Row r = row(label);
        Runnable bind =
                () ->
                        r.value.setText(
                                join(strings(s.optJSONArray(key)), getString(R.string.s_none)));
        bind.run();
        r.view.setOnClickListener(
                v ->
                        load(
                                r,
                                fetch,
                                names -> {
                                    List<String> current = strings(s.optJSONArray(key));
                                    boolean[] checked = new boolean[names.size()];
                                    for (int i = 0; i < checked.length; i++) {
                                        checked[i] = containsIgnoreCase(current, names.get(i));
                                    }
                                    new AlertDialog.Builder(host)
                                            .setTitle(label)
                                            .setMultiChoiceItems(
                                                    names.toArray(new String[0]),
                                                    checked,
                                                    (d, which, on) -> checked[which] = on)
                                            .setPositiveButton(
                                                    R.string.s_save_field,
                                                    (d, w) -> {
                                                        JSONArray picked = new JSONArray();
                                                        for (int i = 0; i < checked.length; i++) {
                                                            if (checked[i]) {
                                                                picked.put(names.get(i));
                                                            }
                                                        }
                                                        put(s, key, picked);
                                                        save();
                                                        bind.run();
                                                    })
                                            .setNegativeButton(R.string.cancel, null)
                                            .show();
                                }));
    }

    /** Like pickMany with one answer; the first entry, the server's default, removes the key. */
    private void pickOne(JSONObject s, String key, int label, Fetch fetch) {
        Row r = row(label);
        Runnable bind =
                () ->
                        r.value.setText(
                                s.optString(key).isEmpty()
                                        ? getString(R.string.s_server_default)
                                        : s.optString(key));
        bind.run();
        r.view.setOnClickListener(
                v ->
                        load(
                                r,
                                fetch,
                                names -> {
                                    List<String> options = new ArrayList<>();
                                    options.add(getString(R.string.s_server_default));
                                    options.addAll(names);
                                    int selected = 0;
                                    for (int i = 1; i < options.size(); i++) {
                                        if (options.get(i).equalsIgnoreCase(s.optString(key))) {
                                            selected = i;
                                        }
                                    }
                                    new AlertDialog.Builder(host)
                                            .setTitle(label)
                                            .setSingleChoiceItems(
                                                    options.toArray(new String[0]),
                                                    selected,
                                                    (d, which) -> {
                                                        if (which == 0) {
                                                            s.remove(key);
                                                        } else {
                                                            put(s, key, options.get(which));
                                                        }
                                                        save();
                                                        bind.run();
                                                        d.dismiss();
                                                    })
                                            .show();
                                }));
    }

    /** A fixed list of choices stored by value; the first is the default and removes the key. */
    private void choice(JSONObject s, String key, int label, String[] values, String[] labels) {
        Row r = row(label);
        Runnable bind =
                () ->
                        r.value.setText(
                                labels[
                                        Math.max(
                                                0,
                                                Arrays.asList(values)
                                                        .indexOf(s.optString(key, values[0])))]);
        bind.run();
        r.view.setOnClickListener(
                v -> {
                    int selected =
                            Math.max(0, Arrays.asList(values).indexOf(s.optString(key, values[0])));
                    new AlertDialog.Builder(host)
                            .setTitle(label)
                            .setSingleChoiceItems(
                                    labels,
                                    selected,
                                    (d, which) -> {
                                        if (which == 0) {
                                            s.remove(key);
                                        } else {
                                            put(s, key, values[which]);
                                        }
                                        save();
                                        bind.run();
                                        d.dismiss();
                                    })
                            .show();
                });
    }

    private interface Check {
        String run() throws Exception;
    }

    /** A client for the section as it is now filled in, or the reason it cannot be one yet. */
    private Immich immich(JSONObject section) throws IOException {
        Config.Immich c = new Config.Immich(section);
        need(c.configured(), R.string.s_fill_in);
        return new Immich(c, host.tls);
    }

    private Arr arr(Arr.Kind kind, JSONObject section) throws IOException {
        Config.Arr c = new Config.Arr(section);
        need(c.configured(), R.string.s_fill_in);
        return new Arr(kind, c, host.tls);
    }

    /**
     * Stops a Test or a picker before it sends a request to an empty address, with words instead of
     * "no protocol".
     */
    private void need(boolean configured, int message) throws IOException {
        if (!configured) {
            throw new IOException(getString(message));
        }
    }

    /**
     * "Test connection": its answer replaces the line under it, green when it connected and red
     * when not.
     */
    private void test(Check check) {
        Row r = row(R.string.s_test);
        r.value.setVisibility(View.GONE);
        r.summary(getString(R.string.s_test_summary), host.getColor(R.color.text_dim));
        r.view.setOnClickListener(
                v -> {
                    r.summary(getString(R.string.s_testing), host.getColor(R.color.text_dim));
                    io.execute(
                            () -> {
                                String msg;
                                boolean ok;
                                try {
                                    msg = check.run();
                                    ok = true;
                                } catch (Exception e) {
                                    msg = e.getMessage();
                                    ok = false;
                                }
                                String m = msg;
                                int color = host.getColor(ok ? R.color.ok : R.color.bad);
                                host.runOnUiThread(() -> r.summary(m, color));
                            });
                });
    }

    private interface Loaded {
        void show(List<String> names);
    }

    /** Fetches a server list off the main thread with the fields as they are now, then shows it. */
    private void load(Row r, Fetch fetch, Loaded loaded) {
        CharSequence before = r.value.getText();
        r.value.setText(R.string.loading);
        io.execute(
                () -> {
                    try {
                        List<String> names = fetch.names();
                        host.runOnUiThread(
                                () -> {
                                    r.value.setText(before);
                                    if (!host.isFinishing()) {
                                        loaded.show(names);
                                    }
                                });
                    } catch (Exception e) {
                        host.runOnUiThread(
                                () -> {
                                    r.value.setText(before);
                                    Toast.makeText(host, e.getMessage(), Toast.LENGTH_LONG).show();
                                });
                    }
                });
    }

    private void action(int label, int summary, Runnable run) {
        Row r = row(label);
        r.value.setVisibility(View.GONE);
        r.view.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
        if (summary != 0) {
            r.summary(getString(summary), host.getColor(R.color.text_dim));
        }
        r.view.setOnClickListener(v -> run.run());
    }

    /** A small accent heading over a group of rows. */
    private void group(int label) {
        TextView t = UpcomingPage.header(host, getString(label));
        t.setPadding(dp(6), dp(26), 0, dp(10));
        pane.addView(t);
    }

    private void note(String text) {
        TextView t = text(text, 15, R.color.text_dim);
        t.setPadding(dp(6), dp(14), dp(6), dp(4));
        t.setLineSpacing(0, 1.2f);
        pane.addView(t);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(host);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(host.getColor(color));
        return t;
    }

    /** The last four characters of a key, enough to tell which one is set. */
    static String mask(String secret) {
        return secret.length() <= 4 ? "••••" : "••••••" + secret.substring(secret.length() - 4);
    }

    static List<String> names(JSONArray items, String key) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            String n = o == null ? "" : o.optString(key).trim();
            if (!n.isEmpty() && !out.contains(n)) {
                out.add(n);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                if (!a.optString(i).isEmpty()) {
                    out.add(a.optString(i));
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String each : list) {
            if (each.toLowerCase(Locale.ROOT).equals(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<String> list, String empty) {
        return list.isEmpty() ? empty : String.join(", ", list);
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
