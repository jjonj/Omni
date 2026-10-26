package com.omni.sync.data.taskboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskBoardDiscoveryTest {
    @Test
    fun canonicalSameNamedFolderWinsWithoutDeletingDistinctBoards() {
        val root = File("fixture")
        val boards = listOf(
            TaskBoardProject("Omni", "OmniHub", File(root, "OmniHub/Tasks")),
            TaskBoardProject("Omni", "Omni", File(root, "Omni/Tasks")),
            TaskBoardProject("Other board", "Other", File(root, "Other/Tasks")),
            TaskBoardProject("Other board", "OtherLegacy", File(root, "OtherLegacy/Tasks")),
            TaskBoardProject("Wartribes", "Wartribes", File(root, "Wartribes/Tasks"))
        )
        assertEquals(listOf("Omni", "Other", "OtherLegacy", "Wartribes"),
            TaskBoardStore.preferCanonicalNamedBoards(boards).map { it.folderName })
    }

    @Test
    fun loneLegacyBoardRemainsVisibleUntilCanonicalArrives() {
        val legacy = TaskBoardProject("Omni", "OmniHub", File("OmniHub/Tasks"))
        assertEquals(listOf(legacy), TaskBoardStore.preferCanonicalNamedBoards(listOf(legacy)))
    }
}
