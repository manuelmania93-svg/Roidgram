package org.telegram.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import kotlin.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Synthetic, repeatable proof of the selection-cap and range-walk logic (no emulator, no account). */
public class SelectBetweenPlannerTest {

    private static List<Integer> range(int fromInclusive, int toInclusive) {
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) out.add(i);
        return out;
    }

    @Test
    public void selectsMoreThan1000() {
        List<Integer> candidates = range(1, 1500); // begin=0, end=1501
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(candidates, new HashSet<Integer>(), 0, 10_000);
        assertEquals(1500, r.getFirst().size());
        assertEquals(Integer.valueOf(1), r.getFirst().get(0));
        assertEquals(Integer.valueOf(1500), r.getFirst().get(1499));
        assertFalse(r.getSecond());
    }

    @Test
    public void capStopsExactlyAtBoundary() {
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(range(1, 2000), new HashSet<Integer>(), 9_950, 10_000);
        assertEquals(50, r.getFirst().size()); // 9950 + 50 = exactly 10000
        assertTrue(r.getSecond());
    }

    @Test
    public void capZeroRoomAddsNothing() {
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(Arrays.asList(5, 6, 7), new HashSet<Integer>(), 10_000, 10_000);
        assertEquals(0, r.getFirst().size());
        assertTrue(r.getSecond());
    }

    @Test
    public void skipsAlreadySelectedAndKeepsAscendingOrder() {
        Set<Integer> selected = new HashSet<>(Arrays.asList(4, 5));
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(Arrays.asList(3, 4, 5, 6), selected, 2, 10_000);
        assertEquals(Arrays.asList(3, 6), r.getFirst());
        assertFalse(r.getSecond());
    }

    @Test
    public void rangeWalkAcrossSparseUnloadedPages() {
        // begin=10, end=900, sparse middle loaded; other-topic ids are absent from the loaded list.
        Integer cursor = SelectBetweenPlanner.INSTANCE.cursor(Arrays.asList(900, 500, 10), 10);
        assertEquals(Integer.valueOf(500), cursor);
        assertNull(SelectBetweenPlanner.INSTANCE.stopReason(cursor, 10, false, -1, false));
        cursor = SelectBetweenPlanner.INSTANCE.cursor(Arrays.asList(900, 500, 100, 10), 10);
        assertEquals(Integer.valueOf(100), cursor);
        assertNull(SelectBetweenPlanner.INSTANCE.stopReason(cursor, 10, false, 500, false));
        cursor = SelectBetweenPlanner.INSTANCE.cursor(Arrays.asList(900, 500, 100, 11, 10), 10);
        assertEquals(Integer.valueOf(11), cursor);
        assertEquals(SelectBetweenPlanner.STOP_COMPLETE,
                (int) SelectBetweenPlanner.INSTANCE.stopReason(10, 10, false, 11, false));
    }

    @Test
    public void stopsWhenHistoryExhausted() {
        assertEquals(SelectBetweenPlanner.STOP_HISTORY_END,
                (int) SelectBetweenPlanner.INSTANCE.stopReason(500, 10, true, 600, false));
    }

    @Test
    public void stopsOnNoProgressDeletedHole() {
        assertEquals(SelectBetweenPlanner.STOP_NO_PROGRESS,
                (int) SelectBetweenPlanner.INSTANCE.stopReason(500, 10, false, 500, false));
    }

    @Test
    public void stopsOnCap() {
        assertEquals(SelectBetweenPlanner.STOP_CAP,
                (int) SelectBetweenPlanner.INSTANCE.stopReason(500, 10, false, 600, true));
    }

    @Test
    public void cancelBoundary() {
        assertTrue(SelectBetweenPlanner.INSTANCE.isCanceled(0));
        assertFalse(SelectBetweenPlanner.INSTANCE.isCanceled(1));
        assertFalse(SelectBetweenPlanner.INSTANCE.isCanceled(10_000));
    }

    @Test
    public void topicIsolationByConstruction() {
        // In a forum, message ids interleave across topics. The caller passes ONLY the
        // current topic's loaded messages; the planner can never select or walk into
        // ids it was not given (other topics), however dense the span is.
        List<Integer> topicALoaded = Arrays.asList(10, 30, 50, 70, 90); // other-topic ids 11..89 absent
        Integer cursor = SelectBetweenPlanner.INSTANCE.cursor(topicALoaded, 10);
        assertEquals(Integer.valueOf(30), cursor); // never "11" (other topic)
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(
                Arrays.asList(30, 50, 70), new HashSet<Integer>(), 0, 10_000);
        assertEquals(Arrays.asList(30, 50, 70), r.getFirst()); // only topic-A ids returned
    }

    @Test
    public void stress100kCandidates() {
        List<Integer> big = range(1, 100_000);
        Pair<List<Integer>, Boolean> r = SelectBetweenPlanner.INSTANCE.addPass(big, new HashSet<Integer>(), 0, 100_000);
        assertEquals(100_000, r.getFirst().size());
        assertFalse(r.getSecond());
        // Cursor over 100k loaded ids picks the true minimum above begin.
        assertEquals(Integer.valueOf(2), SelectBetweenPlanner.INSTANCE.cursor(big, 1));
    }

    @Test
    public void emptyRangeIsComplete() {
        assertNull(SelectBetweenPlanner.INSTANCE.cursor(new ArrayList<Integer>(), 10));
        assertEquals(SelectBetweenPlanner.STOP_COMPLETE,
                (int) SelectBetweenPlanner.INSTANCE.stopReason(null, 10, false, -1, false));
    }
}
