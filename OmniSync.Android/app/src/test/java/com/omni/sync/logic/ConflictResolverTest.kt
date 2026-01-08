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
        
        // Currently this will likely be a conflict. 
        // We want to see if we can make it more robust.
        assertTrue(result.content.contains("[CONFLICT_START]"))
    }
}
