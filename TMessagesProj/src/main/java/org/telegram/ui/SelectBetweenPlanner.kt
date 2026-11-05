package org.telegram.ui

/**
 * Roidgram: Android-free decision logic for the select-between range walk and the
 * multi-select cap, so CI unit tests prove the >1000 / cap / gap-walk / topic-scope
 * behavior without an emulator or a logged-in account. ChatActivity pre-filters
 * candidates (type, AyuFilter, topic membership of the loaded list) and applies the
 * planner's decisions; interactive/server-side behavior is verified separately.
 */
object SelectBetweenPlanner {

    const val STOP_COMPLETE = 0
    const val STOP_HISTORY_END = 1
    const val STOP_CAP = 2
    const val STOP_NO_PROGRESS = 3

    /**
     * Which of [candidateIdsAsc] (ascending, strictly inside begin..end, already
     * type/filter-checked by the caller) to add given the [selectedIds] set and the
     * current [selectedCount]/[cap]. Returns ids to add (ascending) and whether the
     * cap stopped the pass (true = more candidates existed than the cap allowed).
     */
    fun addPass(candidateIdsAsc: List<Int>, selectedIds: Set<Int>, selectedCount: Int, cap: Int): Pair<List<Int>, Boolean> {
        val out = ArrayList<Int>()
        var count = selectedCount
        var capHit = false
        for (id in candidateIdsAsc) {
            if (selectedIds.contains(id)) continue
            if (count >= cap) {
                capHit = true
                break
            }
            out.add(id)
            count++
        }
        return Pair(out, capHit)
    }

    /** Leaving selection mode empties the selection: the walk cancels instead of loading in the background. */
    fun isCanceled(selectedCount: Int): Boolean = selectedCount == 0

    /** Next page cursor: smallest loaded id greater than [begin]; null when the range is not loaded at all. */
    fun cursor(loadedIdsInRange: List<Int>, begin: Int): Int? {
        var best: Int? = null
        for (id in loadedIdsInRange) {
            if (id > begin && (best == null || id < best)) best = id
        }
        return best
    }

    /**
     * Whether the gap-fill walk must stop, and why. [lastCursor] == current cursor means
     * the last page produced no older message (deleted-id hole or empty page): stop instead
     * of looping forever.
     */
    fun stopReason(cursor: Int?, begin: Int, historyEndReached: Boolean, lastCursor: Int, capHit: Boolean): Int? {
        if (capHit) return STOP_CAP
        if (cursor == null || cursor <= begin) return STOP_COMPLETE
        if (historyEndReached) return STOP_HISTORY_END
        if (cursor == lastCursor) return STOP_NO_PROGRESS
        return null
    }
}
