package com.omni.sync.logic

data class MergeResult(
    val content: String,
    val conflictCount: Int,
    val totalLines: Int
) {
    val conflictRatio: Float
        get() = if (totalLines > 0) conflictCount.toFloat() / totalLines else 0f
}

class ConflictResolver {
    fun merge(local: String, remote: String): MergeResult {
        val localLines = local.lines()
        val remoteLines = remote.lines()
        val result = mutableListOf<String>()
        
        val maxLines = maxOf(localLines.size, remoteLines.size)
        var conflictCount = 0

        for (i in 0 until maxLines) {
            val localLine = localLines.getOrNull(i)
            val remoteLine = remoteLines.getOrNull(i)

            when {
                localLine == remoteLine -> {
                    if (localLine != null) result.add(localLine)
                }
                localLine != null && remoteLine != null -> {
                    result.add("[CONFLICT_START]")
                    result.add("[LOCAL]: $localLine")
                    result.add("[REMOTE]: $remoteLine")
                    result.add("[CONFLICT_END]")
                    conflictCount++
                }
                localLine != null -> {
                    result.add("[LOCAL_ONLY]: $localLine")
                    conflictCount++ // Treat additions as conflicts for ratio purposes? 
                    // Actually, additions might be okay. But the task says "mismatch".
                    // Let's count them as conflicts if we want to be safe.
                }
                remoteLine != null -> {
                    result.add("[REMOTE_ONLY]: $remoteLine")
                    conflictCount++
                }
            }
        }
        
        return MergeResult(result.joinToString("\n"), conflictCount, maxLines)
    }
}