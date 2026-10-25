package io.github.epochcoder.homelab;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The companion's settings, read from {@code config.json} in private storage. {@link SettingsPage}
 * edits it on the TV; companion/push-config.sh can write it in one go through {@link
 * ConfigProvider}. Every section is optional; a feature whose section is missing or has no url
 * shows "not set up" instead of failing. companion/config.example.json lists every key.
 */
final class Config {
    private static final String TAG = "Homelab.Config";
    static final String FILE_NAME = "config.json";

    /**
     * Extra CA certificate(s), pushed on their own by push-config.sh --ca so on-TV settings stay
     * untouched.
     */
    static final String CA_FILE_NAME = "ca.pem";

    /**
     * PEM certificate(s) trusted in addition to the system CAs, Caddy's internal root for one:
     * config.json's ca_pem and the ca.pem file together. Empty if none.
     */
    final String caPem;

    final Immich immich;
    final Homepage homepage;
    final Arr sonarr;
    final Arr radarr;
    final QBittorrent qbittorrent;

    static final class Immich {
        final String url;
        final String apiKey;

        /** Photos taken in the last this-many days. */
        final int recentDays;

        /** Upper bound on the photos fetched per dream, newest first. */
        final int maxPhotos;

        /** Album names (case-insensitive) or ids whose photos never show. */
        final List<String> excludeAlbums;

        /** Album names or ids to show exclusively. If set, excludeAlbums is ignored. */
        final List<String> includeAlbums;

        /** Tag names or full tag paths ("People/Work"), or ids, whose photos never show. */
        final List<String> excludeTags;

        final boolean shuffle;
        final int intervalSeconds;

        /**
         * Fill the screen with every landscape photo, cropping its edges. Off, only photos close to
         * the screen's shape fill and the rest fit over a blurred copy of themselves.
         */
        final boolean crop;

        final boolean showClock;

        /** The album, date, place and people under each photo. */
        final boolean details;

        /** A slow zoom across each photo while it is on screen. */
        final boolean motion;

        /** Two portrait photos side by side instead of one with wide margins. */
        final boolean pairPortraits;

        Immich(JSONObject o) {
            url = baseUrl(o.optString("url"));
            apiKey = o.optString("api_key").trim();
            recentDays = clamp(o.optInt("recent_days", 90), 1, 36500);
            maxPhotos = clamp(o.optInt("max_photos", 500), 1, 5000);
            excludeAlbums = strings(o.optJSONArray("exclude_albums"));
            includeAlbums = strings(o.optJSONArray("include_albums"));
            excludeTags = strings(o.optJSONArray("exclude_tags"));
            shuffle = o.optBoolean("shuffle", true);
            intervalSeconds = clamp(o.optInt("interval_seconds", 20), 5, 3600);
            crop = o.optBoolean("crop", false);
            showClock = o.optBoolean("show_clock", true);
            details = o.optBoolean("details", true);
            motion = o.optBoolean("motion", true);
            pairPortraits = o.optBoolean("pair_portraits", true);
        }

        boolean configured() {
            return !url.isEmpty() && !apiKey.isEmpty();
        }
    }

    static final class Homepage {
        final String url;

        /** WebView text zoom in percent; Homepage's desktop layout can read small from a sofa. */
        final int textZoom;

        /** Keep the screen on while the dashboard is open, for a wall display. */
        final boolean keepAwake;

        Homepage(JSONObject o) {
            url = baseUrl(o.optString("url"));
            textZoom = clamp(o.optInt("text_zoom", 100), 50, 300);
            keepAwake = o.optBoolean("keep_awake", false);
        }

        boolean configured() {
            return !url.isEmpty();
        }
    }

    /** Sonarr or Radarr: the same API shape (v3) for both. */
    static final class Arr {
        final String url;
        final String apiKey;

        /** Quality profile name to add with; empty takes the first profile the server lists. */
        final String qualityProfile;

        /** Root folder path to add into; empty takes the first root folder the server lists. */
        final String rootFolder;

        /**
         * Sonarr only: which episodes a new show monitors (Arr.SERIES_MONITOR); "all" unless set.
         */
        final String monitor;

        Arr(JSONObject o) {
            url = baseUrl(o.optString("url"));
            apiKey = o.optString("api_key").trim();
            qualityProfile = o.optString("quality_profile").trim();
            rootFolder = o.optString("root_folder").trim();
            String m = o.optString("monitor").trim();
            monitor =
                    java.util.Arrays.asList(io.github.epochcoder.homelab.Arr.SERIES_MONITOR)
                                    .contains(m)
                            ? m
                            : "all";
        }

        boolean configured() {
            return !url.isEmpty() && !apiKey.isEmpty();
        }
    }

    /**
     * qBittorrent's WebUI. No user means qBittorrent lets this address in without a login
     * (whitelisted subnet).
     */
    static final class QBittorrent {
        final String url;
        final String username;
        final String password;

        QBittorrent(JSONObject o) {
            url = baseUrl(o.optString("url"));
            username = o.optString("username").trim();
            password = o.optString("password");
        }

        boolean configured() {
            return !url.isEmpty();
        }
    }

    private Config(JSONObject o, String caFile) {
        caPem = (o.optString("ca_pem").trim() + "\n" + caFile.trim()).trim();
        immich = new Immich(section(o, "immich"));
        homepage = new Homepage(section(o, "homepage"));
        sonarr = new Arr(section(o, "sonarr"));
        radarr = new Arr(section(o, "radarr"));
        qbittorrent = new QBittorrent(section(o, "qbittorrent"));
    }

    /** The config in {@code json}, or an empty one (nothing configured) when it does not parse. */
    static Config parse(String json) {
        return parse(json, "");
    }

    /**
     * As {@link #parse(String)}, with the separately pushed ca.pem added to the trusted
     * certificates.
     */
    static Config parse(String json, String caFile) {
        try {
            return new Config(new JSONObject(json), caFile);
        } catch (JSONException | NullPointerException e) {
            Log.w(TAG, "config.json does not parse, nothing is configured: " + e.getMessage());
            return new Config(new JSONObject(), caFile);
        }
    }

    /**
     * Read on every activity start and dream, so a change in Settings or a push-config.sh takes
     * effect at once.
     */
    static Config load(Context ctx) {
        return parse(read(ctx, FILE_NAME), read(ctx, CA_FILE_NAME));
    }

    /**
     * The file as SettingsPage edits it: every key kept, including ones the screen does not show.
     */
    static JSONObject loadJson(Context ctx) {
        try {
            return new JSONObject(read(ctx, FILE_NAME));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * Written next to the old file and renamed over it, so a dream reading it never sees half a
     * file.
     */
    static void save(Context ctx, JSONObject json) throws IOException {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        File tmp = new File(ctx.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("cannot replace " + f);
        }
    }

    /**
     * A file in private storage, "{}" for config.json and "" for anything else when it is missing.
     */
    private static String read(Context ctx, String name) {
        String missing = FILE_NAME.equals(name) ? "{}" : "";
        File f = new File(ctx.getFilesDir(), name);
        if (!f.isFile()) {
            return missing;
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + f + ": " + e.getMessage());
            return missing;
        }
    }

    private static JSONObject section(JSONObject o, String name) {
        JSONObject s = o.optJSONObject(name);
        return s != null ? s : new JSONObject();
    }

    /**
     * "https://photos.lan/" and "https://photos.lan" are the same server; anything but http(s) is
     * refused.
     */
    static String baseUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? u : "";
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
