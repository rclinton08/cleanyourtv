package io.github.epochcoder.homelab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Immich API calls the screensaver and Settings need, against the search API of Immich 3.2 and
 * later ({@code filter}, {@code orderBy}, {@code cursor}). The API key needs only these
 * permissions: asset.read, asset.view, album.read and tag.read.
 *
 * <p>Hidden albums and tags are resolved to ids once per round and filtered out by the server
 * ({@code albumIds: {none: [...]}}), so a hidden photo is never even listed.
 */
final class Immich {
    /**
     * Search page size; the API takes up to 1000, smaller pages keep each response light on a TV.
     */
    static final int PAGE_SIZE = 250;

    /** Safety stop on paging, whatever the server says about a next page. */
    private static final int MAX_PAGES = 40;

    /**
     * One photo as the screensaver captions it. Strings are empty, never null, when Immich has no
     * value.
     */
    static final class Photo {
        final String id;

        /**
         * The photographer's local time, timezone-agnostic: "2025-06-14T15:32:10.000Z" means 15:32
         * where it was taken.
         */
        final String localDateTime;

        final String city;
        final String state;
        final String country;
        final String description;

        /** Named, visible people, in Immich's order. */
        final List<String> people;

        Photo(
                String id,
                String localDateTime,
                String city,
                String state,
                String country,
                String description,
                List<String> people) {
            this.id = id;
            this.localDateTime = localDateTime;
            this.city = city;
            this.state = state;
            this.country = country;
            this.description = description;
            this.people = people;
        }
    }

    private final Config.Immich cfg;
    private final Http http;

    Immich(Config.Immich cfg, SSLSocketFactory tls) {
        this.cfg = cfg;
        this.http = new Http(tls, "x-api-key", cfg.apiKey);
    }

    /**
     * The photos to show, newest first, hidden albums and tags left out by the server, at most
     * max_photos.
     */
    List<Photo> recentPhotos(long nowMillis) throws IOException {
        List<String> albums;
        if (!cfg.includeAlbums.isEmpty()) {
            albums = resolveIds(albums(), cfg.includeAlbums, "albumName");
        } else {
            albums = cfg.excludeAlbums.isEmpty()
                            ? Collections.emptyList()
                            : resolveIds(albums(), cfg.excludeAlbums, "albumName");
        }
        // "name" is the tag itself, "value" its full path (People/Work); either may be configured.
        List<String> tags =
                cfg.excludeTags.isEmpty()
                        ? Collections.emptyList()
                        : resolveIds(tags(), cfg.excludeTags, "name", "value");
        String after = takenAfter(nowMillis, cfg.recentDays);
        List<Photo> photos = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES && photos.size() < cfg.maxPhotos; page++) {
            int size = Math.min(PAGE_SIZE, cfg.maxPhotos - photos.size());
            Page p =
                    parsePage(
                            http.post(
                                    cfg.url + "/api/search/metadata",
                                    searchBody(after, cursor, size, albums, tags, !cfg.includeAlbums.isEmpty()).toString()));
            photos.addAll(p.photos);
            cursor = p.nextCursor;
            if (cursor == null) {
                break;
            }
        }
        return photos.size() > cfg.maxPhotos
                ? new ArrayList<>(photos.subList(0, cfg.maxPhotos))
                : photos;
    }

    /** Every album the key's user owns or has been shared: objects with id and albumName. */
    JSONArray albums() throws IOException {
        return array(http.get(cfg.url + "/api/albums"));
    }

    /** Every tag: objects with id, name and value (the full path). */
    JSONArray tags() throws IOException {
        return array(http.get(cfg.url + "/api/tags"));
    }

    /**
     * The name of an album holding the photo, or "" when it is in none. One small request per photo
     * shown.
     */
    String albumOf(String assetId) throws IOException {
        JSONArray a = array(http.get(cfg.url + "/api/albums?assetId=" + Http.encode(assetId)));
        for (int i = 0; i < a.length(); i++) {
            JSONObject album = a.optJSONObject(i);
            String name = album == null ? "" : album.optString("albumName").trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "";
    }

    /**
     * The API key permissions the screensaver uses, each with the request that needs it. Settings
     * names them next to the key, and {@link #missingPermissions} tries each one.
     */
    static final String[] PERMISSIONS = {"asset.read", "asset.view", "album.read", "tag.read"};

    /**
     * The permissions from {@link #PERMISSIONS} this key lacks, found by making each request once:
     * Immich answers 403 for a permission the key was not given. Any other failure (unreachable,
     * wrong key, a certificate) is thrown, since then no permission can be judged.
     */
    List<String> missingPermissions() throws IOException {
        List<String> missing = new ArrayList<>();
        if (forbidden(() -> albums())) {
            missing.add("album.read");
        }
        if (forbidden(() -> tags())) {
            missing.add("tag.read");
        }
        String[] newest = {null};
        boolean noRead = forbidden(() -> newest[0] = newestPhotoId());
        if (noRead) {
            missing.add("asset.read");
        }
        // asset.view can only be tried on a photo the key can list; without asset.read it stays
        // unknown, and the list above already says what to fix first.
        if (newest[0] != null && forbidden(() -> preview(newest[0]))) {
            missing.add("asset.view");
        }
        return missing;
    }

    /** The newest photo on the timeline, or null when there is none. */
    private String newestPhotoId() throws IOException {
        String everything = takenAfter(System.currentTimeMillis(), 36500);
        JSONObject body =
                searchBody(everything, null, 1, Collections.emptyList(), Collections.emptyList(), false);
        Page p = parsePage(http.post(cfg.url + "/api/search/metadata", body.toString()));
        return p.photos.isEmpty() ? null : p.photos.get(0).id;
    }

    private interface Call {
        void run() throws IOException;
    }

    private static boolean forbidden(Call call) throws IOException {
        try {
            call.run();
            return false;
        } catch (IOException e) {
            String m = e.getMessage();
            if (m != null && m.startsWith("HTTP 403")) {
                return true;
            }
            throw e;
        }
    }

    /**
     * The preview rendition (about 1440 px on the long side): sharp on a projector, a fraction of
     * the original.
     */
    byte[] preview(String assetId) throws IOException {
        return http.bytes(
                cfg.url + "/api/assets/" + Http.encode(assetId) + "/thumbnail?size=preview");
    }

    /**
     * POST /api/search/metadata body: images on the timeline taken after {@code after}, newest
     * first, with the EXIF and people the captions use, minus the given albums and tags.
     */
    static JSONObject searchBody(
            String after, String cursor, int size, List<String> hideAlbums, List<String> hideTags, boolean includeOnly) {
        try {
            JSONObject filter =
                    new JSONObject()
                            .put("type", new JSONObject().put("eq", "IMAGE"))
                            // Archived, hidden and locked-folder photos stay out of a screensaver.
                            .put("visibility", new JSONObject().put("eq", "timeline"))
                            .put("takenAt", new JSONObject().put("gte", after));
            if (!hideAlbums.isEmpty()) {
                filter.put("albumIds", new JSONObject().put(includeOnly ? "any" : "none", new JSONArray(hideAlbums)));
            }
            if (!hideTags.isEmpty()) {
                filter.put("tagIds", new JSONObject().put("none", new JSONArray(hideTags)));
            }
            JSONObject body =
                    new JSONObject()
                            .put("filter", filter)
                            .put(
                                    "orderBy",
                                    new JSONObject()
                                            .put("field", "localDateTime")
                                            .put("direction", "desc"))
                            .put("size", size)
                            .put("withExif", true)
                            .put("withPeople", true);
            if (cursor != null) {
                body.put("cursor", cursor);
            }
            android.util.Log.d("ImmichDebug", "Search Body: " + body.toString());
            return body;
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
    }

    static final class Page {
        final List<Photo> photos;

        /** Null on the last page. */
        final String nextCursor;

        Page(List<Photo> photos, String nextCursor) {
            this.photos = photos;
            this.nextCursor = nextCursor;
        }
    }

    /** The photos in a search response, and the cursor of the next page if there is one. */
    static Page parsePage(String json) throws IOException {
        try {
            JSONObject assets = new JSONObject(json).getJSONObject("assets");
            JSONArray items = assets.optJSONArray("items");
            List<Photo> photos = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject a = items.optJSONObject(i);
                    if (a != null && !a.optString("id").isEmpty()) {
                        photos.add(parsePhoto(a));
                    }
                }
            }
            String next = assets.isNull("nextCursor") ? "" : assets.optString("nextCursor");
            // A next page with nothing on this one stops the paging, whatever the server claims.
            return new Page(photos, next.isEmpty() || photos.isEmpty() ? null : next);
        } catch (JSONException e) {
            throw new IOException(
                    "unexpected search response from Immich (Immich 3.2 or later is needed): "
                            + e.getMessage());
        }
    }

    static Photo parsePhoto(JSONObject a) {
        JSONObject exif = a.optJSONObject("exifInfo");
        if (exif == null) {
            exif = new JSONObject();
        }
        List<String> people = new ArrayList<>();
        JSONArray ps = a.optJSONArray("people");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                String name = p == null ? "" : text(p, "name");
                if (!name.isEmpty() && !p.optBoolean("isHidden") && !people.contains(name)) {
                    people.add(name);
                }
            }
        }
        return new Photo(
                a.optString("id"),
                text(a, "localDateTime"),
                text(exif, "city"),
                text(exif, "state"),
                text(exif, "country"),
                text(exif, "description"),
                people);
    }

    /** A string field, with JSON null and whitespace read as "". */
    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key).trim();
    }

    /**
     * Ids of the items in {@code items} matching any of {@code wanted}, by id or by one of the name
     * fields, names compared without case. An entry that matches nothing is ignored: a renamed
     * album just stops hiding.
     */
    static List<String> resolveIds(JSONArray items, List<String> wanted, String... nameKeys) {
        Set<String> want = new HashSet<>();
        for (String w : wanted) {
            want.add(w.toLowerCase(Locale.ROOT));
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id");
            if (id.isEmpty()) {
                continue;
            }
            boolean match = want.contains(id.toLowerCase(Locale.ROOT));
            for (String k : nameKeys) {
                match |= want.contains(item.optString(k).toLowerCase(Locale.ROOT));
            }
            if (match && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Midnight UTC {@code days} days before {@code nowMillis}, in the ISO form the API takes. */
    static String takenAfter(long nowMillis, int days) {
        return Format.utcMidnight(nowMillis, -days);
    }

    private static JSONArray array(String json) throws IOException {
        try {
            return new JSONArray(json);
        } catch (JSONException e) {
            throw new IOException("unexpected response from Immich: " + e.getMessage());
        }
    }
}
