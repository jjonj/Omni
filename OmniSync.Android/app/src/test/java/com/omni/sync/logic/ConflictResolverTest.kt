package com.omni.sync.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    @Test
    fun `test merge identical files`() {
        val resolver = ConflictResolver()
        val local = "line1\nline2"
        val remote = "line1\nline2"
        val result = resolver.merge(local, remote)
        assertEquals("line1\nline2", result.content)
        assertEquals(0, result.conflictCount)
        assertEquals(0f, result.conflictRatio)
    }

    @Test
    fun `test merge with conflicts`() {
        val resolver = ConflictResolver()
        val local = "line1\nlocal_line2"
        val remote = "line1\nremote_line2"
        val result = resolver.merge(local, remote)
        
        assertTrue(result.content.contains("[CONFLICT_START]"))
        assertTrue(result.content.contains("[LOCAL]: local_line2"))
        assertTrue(result.content.contains("[REMOTE]: remote_line2"))
        assertTrue(result.content.contains("[CONFLICT_END]"))
        assertEquals(1, result.conflictCount)
        assertEquals(0.5f, result.conflictRatio)
    }

    @Test
    fun `test merge with local only lines`() {
        val resolver = ConflictResolver()
        val local = "line1\nline2"
        val remote = "line1"
        val result = resolver.merge(local, remote)
        assertTrue(result.content.contains("[LOCAL_ONLY]: line2"))
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun `test merge insertion at start`() {
        val resolver = ConflictResolver()
        val local = "A\nB\nC"
        val remote = "X\nA\nB\nC"
        val result = resolver.merge(local, remote)
        
        // Expected: X is detected as new (Remote Only), A, B, C are matched.
        assertTrue("Content should contain A without conflict markers around it", result.content.contains("\nA\n") || result.content.startsWith("A\n"))
        assertEquals(0, result.conflictCount)
        assertTrue(result.content.contains("[REMOTE_ONLY]: X"))
    }

    @Test
    fun `test merge with trailing whitespace differences`() {
        val resolver = ConflictResolver()
        val local = "line1\nline2"
        val remote = "line1 \nline2" // Note the space after line1
        val result = resolver.merge(local, remote)
        
        // Should NOT be a conflict now
        assertEquals("line1 \nline2", result.content)
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun `test merge with reordered lines`() {
        val resolver = ConflictResolver()
        val local = "Task A\nTask B\nTask C"
        val remote = "Task B\nTask A\nTask C"
        val result = resolver.merge(local, remote)
        
        // LCS should find "Task B", "Task C" or "Task A", "Task C"
        // It shouldn't be one giant conflict.
        assertTrue("Should not be a single giant conflict", result.conflictRatio < 1.0f)
        assertTrue(result.content.contains("Task C"))
        // We expect Task C to be a Match at the end.
    }

    @Test
    fun `test merge with many small changes`() {
        val resolver = ConflictResolver()
        val local = """
            # Header
            Task 1
            Task 2
            Task 3
            Task 4
        """.trimIndent()
        
        val remote = """
            # Header
            ✅ Task 1
            Task 2
            ✅ Task 3
            Task 4
        """.trimIndent()
        
        val result = resolver.merge(local, remote)
        
        // Header, Task 2 and Task 4 should be matches.
        // Task 1 and Task 3 should be conflicts or substitutions.
        assertTrue(result.content.contains("# Header"))
        assertTrue(result.content.contains("Task 2"))
        assertTrue(result.content.contains("Task 4"))
        assertEquals(2, result.conflictCount)
    }

    @Test
    fun `test merge total mismatch`() {
        val resolver = ConflictResolver()
        val local = "A\nB\nC"
        val remote = "D\nE\nF"
        val result = resolver.merge(local, remote)
        
        // Should be a single giant conflict if there are no matches
        assertTrue(result.content.contains("[CONFLICT_START]"))
        assertEquals(3, result.conflictCount)
    }

    @Test
    fun `test merge with large shifts and few anchors`() {
        val resolver = ConflictResolver()
        val local = """
            Header
            Old Task 1
            Old Task 2
            Footer
        """.trimIndent()
        
        val remote = """
            Header
            New Task 1
            New Task 2
            New Task 3
            Footer
        """.trimIndent()
        
        val result = resolver.merge(local, remote)
        
        // Expected: Header and Footer are matched. Middle is a conflict.
        assertTrue(result.content.startsWith("Header"))
        assertTrue(result.content.endsWith("Footer"))
        assertTrue(result.content.contains("[CONFLICT_START]"))
        
        // Verify middle is a single conflict block
        val conflictBlocks = result.content.split("[CONFLICT_START]").size - 1
        assertEquals("Should have exactly one conflict block in the middle", 1, conflictBlocks)
    }
}
