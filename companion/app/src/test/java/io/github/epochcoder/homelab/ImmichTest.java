package io.github.epochcoder.homelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ImmichTest {
    @Test
    public void searchBodyUsesTheFilterApi() throws Exception {
        JSONObject b =
                Immich.searchBody(
                        "2026-01-01T00:00:00.000Z",
                        null,
                        250,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        false);
        JSONObject f = b.getJSONObject("filter");
        assertEquals("IMAGE", f.getJSONObject("type").getString("eq"));
        assertEquals("timeline", f.getJSONObject("visibility").getString("eq"));
        assertEquals("2026-01-01T00:00:00.000Z", f.getJSONObject("takenAt").getString("gte"));
        assertFalse(f.has("albumIds"));
        assertFalse(f.has("tagIds"));
        assertEquals("localDateTime", b.getJSONObject("orderBy").getString("field"));
        assertEquals("desc", b.getJSONObject("orderBy").getString("direction"));
        assertEquals(250, b.getInt("size"));
        assertTrue(b.getBoolean("withExif"));
        assertTrue(b.getBoolean("withPeople"));
        assertFalse(b.has("cursor"));
        // None of the fields Immich 3.2 deprecated.
        for (String old :
                new String[] {
                    "page", "takenAfter", "albumIds", "tagIds", "order", "visibility", "type"
                }) {
            assertFalse(old, b.has(old));
        }
    }

    @Test
    public void hiddenAlbumsAndTagsAreFilteredByTheServer() throws Exception {
        JSONObject f =
                Immich.searchBody(
                                "x",
                                "c2",
                                10,
                                Arrays.asList("a1", "a2"),
                                Collections.singletonList("t1"),
                                false)
                        .getJSONObject("filter");
        assertEquals(
                new JSONArray("[\"a1\",\"a2\"]").toString(),
                f.getJSONObject("albumIds").getJSONArray("none").toString());
        assertEquals("t1", f.getJSONObject("tagIds").getJSONArray("none").getString(0));
        assertEquals(
                "c2",
                Immich.searchBody("x", "c2", 10, Collections.emptyList(), Collections.emptyList(), false)
                        .getString("cursor"));
    }

    @Test
    public void parsesPagesAndPhotos() throws Exception {
        Immich.Page p =
                Immich.parsePage(
                        "{\"albums\":{},\"assets\":{\"count\":2,\"nextCursor\":\"abc\",\"items\":["
                            + "{\"id\":\"a\",\"localDateTime\":\"2025-06-14T15:32:10.000Z\",\"exifInfo\":{\"city\":\"Cape"
                            + " Town\",\"state\":\"Western Cape\",\"country\":\"South"
                            + " Africa\",\"description\":null},"
                            + "\"people\":[{\"name\":\"Anna\",\"isHidden\":false},{\"name\":\"\"},{\"name\":\"Ghost\",\"isHidden\":true},{\"name\":\"Ben\"}]},"
                            + "{\"id\":\"b\"},{\"nope\":1}]}}");
        assertEquals(2, p.photos.size());
        assertEquals("abc", p.nextCursor);
        Immich.Photo a = p.photos.get(0);
        assertEquals("2025-06-14T15:32:10.000Z", a.localDateTime);
        assertEquals("Cape Town", a.city);
        assertEquals("", a.description);
        assertEquals(Arrays.asList("Anna", "Ben"), a.people);
        Immich.Photo b = p.photos.get(1);
        assertEquals("", b.city);
        assertTrue(b.people.isEmpty());

        assertNull(
                Immich.parsePage("{\"assets\":{\"items\":[{\"id\":\"a\"}],\"nextCursor\":null}}")
                        .nextCursor);
        // A next page with nothing on this one stops the paging, whatever the server claims.
        assertNull(Immich.parsePage("{\"assets\":{\"items\":[],\"nextCursor\":\"x\"}}").nextCursor);
    }

    @Test(expected = java.io.IOException.class)
    public void unexpectedSearchResponseIsAnIOException() throws Exception {
        Immich.parsePage("{\"items\":[]}");
    }

    @Test
    public void resolvesExclusionsByNameOrIdIgnoringCase() throws Exception {
        JSONArray albums =
                new JSONArray(
                        "[{\"id\":\"1\",\"albumName\":\"Screenshots\"},"
                            + "{\"id\":\"2\",\"albumName\":\"Holiday\"},{\"id\":\"3\",\"albumName\":\"Work\"}]");
        assertEquals(
                Arrays.asList("1", "3"),
                Immich.resolveIds(albums, Arrays.asList("screenshots", "3", "gone"), "albumName"));

        JSONArray tags =
                new JSONArray(
                        "[{\"id\":\"t1\",\"name\":\"Work\",\"value\":\"People/Work\"},"
                                + "{\"id\":\"t2\",\"name\":\"Kids\",\"value\":\"People/Kids\"}]");
        assertEquals(
                Collections.singletonList("t1"),
                Immich.resolveIds(tags, Collections.singletonList("people/work"), "name", "value"));
        assertEquals(
                Collections.singletonList("t2"),
                Immich.resolveIds(tags, Collections.singletonList("KIDS"), "name", "value"));
    }

    @Test
    public void takenAfterIsMidnightUtcDaysAgo() {
        long oct21Noon = 1792584000000L; // 2026-10-21T12:00:00Z
        assertEquals("2026-10-21T00:00:00.000Z", Immich.takenAfter(oct21Noon, 0));
        assertEquals("2026-07-23T00:00:00.000Z", Immich.takenAfter(oct21Noon, 90));
    }
}
