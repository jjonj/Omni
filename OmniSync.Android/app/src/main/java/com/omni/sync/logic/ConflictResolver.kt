package com.omni.sync.logic

import kotlin.math.max

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
        val n = localLines.size
        val m = remoteLines.size
        
        // DP for LCS
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                if (localLines[i - 1] == remoteLines[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack
        var i = n
        var j = m
        val operations = mutableListOf<Op>()
        
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && localLines[i - 1] == remoteLines[j - 1]) {
                operations.add(Op.Match(localLines[i - 1]))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                operations.add(Op.Remote(remoteLines[j - 1]))
                j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                operations.add(Op.Local(localLines[i - 1]))
                i--
            }
        }
        operations.reverse()

        val result = mutableListOf<String>()
        var conflictCount = 0
        
        // Process operations to group conflicts
        var k = 0
        while (k < operations.size) {
            val op = operations[k]
            if (op is Op.Match) {
                result.add(op.line)
                k++
            } else {
                // Collect block of non-matches
                val localBlock = mutableListOf<String>()
                val remoteBlock = mutableListOf<String>()
                
                while (k < operations.size && operations[k] !is Op.Match) {
                    when (val current = operations[k]) {
                        is Op.Local -> localBlock.add(current.line)
                        is Op.Remote -> remoteBlock.add(current.line)
                        else -> {} // Should not happen
                    }
                    k++
                }
                
                if (localBlock.isNotEmpty() && remoteBlock.isNotEmpty()) {
                    // Conflict (Substitution)
                    result.add("[CONFLICT_START]")
                    localBlock.forEach { result.add("[LOCAL]: $it") }
                    remoteBlock.forEach { result.add("[REMOTE]: $it") }
                    result.add("[CONFLICT_END]")
                    conflictCount += max(localBlock.size, remoteBlock.size)
                } else if (localBlock.isNotEmpty()) {
                    // Local only (Deletion in remote OR Addition in local)
                    localBlock.forEach { result.add("[LOCAL_ONLY]: $it") }
                } else if (remoteBlock.isNotEmpty()) {
                    // Remote only (Addition in remote OR Deletion in local)
                    remoteBlock.forEach { result.add("[REMOTE_ONLY]: $it") }
                }
            }
        }

        return MergeResult(result.joinToString("\n"), conflictCount, max(n, m))
    }

    private sealed class Op {
        data class Match(val line: String) : Op()
        data class Local(val line: String) : Op()
        data class Remote(val line: String) : Op()
    }
}