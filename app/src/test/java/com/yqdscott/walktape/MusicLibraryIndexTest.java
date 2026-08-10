package com.yqdscott.walktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class MusicLibraryIndexTest {

    @Test
    public void diffIdentifiesAddsUpdatesAndDeletesWithoutReplacingEverything() {
        Map<Long, MusicLibrary.TrackRecord> oldRecords = new LinkedHashMap<>();
        oldRecords.put(1L, record(1, "First", 10));
        oldRecords.put(2L, record(2, "Second", 10));
        oldRecords.put(3L, record(3, "Removed", 10));

        Map<Long, MusicLibrary.TrackRecord> freshRecords = new LinkedHashMap<>();
        freshRecords.put(1L, record(1, "First", 10));
        freshRecords.put(2L, record(2, "Second (Remastered)", 11));
        freshRecords.put(4L, record(4, "Added", 12));

        MusicLibrary.Diff diff = MusicLibrary.Diff.between(oldRecords, freshRecords);

        assertTrue(diff.hasChanges());
        assertEquals(1, diff.added);
        assertEquals(1, diff.changed);
        assertEquals(1, diff.removed);
        assertEquals(2, diff.upserts.size());
        assertEquals(Long.valueOf(3), diff.removals.get(0));
    }

    @Test
    public void unchangedSnapshotIsANoOp() {
        Map<Long, MusicLibrary.TrackRecord> records = new LinkedHashMap<>();
        records.put(8L, record(8, "Stable", 20));

        MusicLibrary.Diff diff = MusicLibrary.Diff.between(records,
                new LinkedHashMap<>(records));

        assertFalse(diff.hasChanges());
        assertEquals(0, diff.upserts.size());
        assertEquals(0, diff.removals.size());
    }

    private static MusicLibrary.TrackRecord record(long id, String title, long modified) {
        return new MusicLibrary.TrackRecord(
                id,
                title,
                "Artist",
                "Album",
                99,
                180_000,
                (int) id,
                1979,
                modified,
                1_024);
    }
}
