package com.omni.sync.ui.screen

/** Render small tool edit diffs without depending on the removed legacy AI screen. */
fun computeInterleavedDiff(old: String, new: String): List<Pair<String, String>> {
    val oldLines = old.split("\n")
    val newLines = new.split("\n")
    val result = mutableListOf<Pair<String, String>>()
    var first = 0
    while (first < oldLines.size && first < newLines.size && oldLines[first] == newLines[first]) {
        result.add("UNCHANGED" to oldLines[first])
        first++
    }

    var oldEnd = oldLines.lastIndex
    var newEnd = newLines.lastIndex
    val trailing = mutableListOf<Pair<String, String>>()
    while (oldEnd >= first && newEnd >= first && oldLines[oldEnd] == newLines[newEnd]) {
        trailing.add(0, "UNCHANGED" to oldLines[oldEnd])
        oldEnd--
        newEnd--
    }
    for (i in first..oldEnd) result.add("DEL" to oldLines[i])
    for (i in first..newEnd) result.add("ADD" to newLines[i])
    result.addAll(trailing)
    return result
}
