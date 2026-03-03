package com.omni.sync.logic

import android.content.Context
import com.omni.sync.data.model.FileSystemEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.io.File
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class BookDownloadManagerTest {

    @Mock
    private lateinit var context: Context
    @Mock
    private lateinit var mainViewModel: com.omni.sync.viewmodel.MainViewModel
    
    private lateinit var downloadManager: BookDownloadManager
    private lateinit var tempDir: File

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        tempDir = File(System.getProperty("java.io.tmpdir"), "omni_books_test")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        
        `when`(context.getExternalFilesDir("downloaded_books")).thenReturn(tempDir)
        downloadManager = BookDownloadManager(context, mainViewModel)
    }

    @Test
    fun `test isDownloaded returns true when file exists`() {
        val fileName = "test.epub"
        val bookFile = File(tempDir, fileName)
        bookFile.writeText("content")
        
        assertTrue(downloadManager.isDownloaded("any/path/$fileName"))
    }

    @Test
    fun `test isDownloaded returns false when file does not exist`() {
        assertFalse(downloadManager.isDownloaded("any/path/missing.epub"))
    }

    @Test
    fun `test getLocalPath returns correct path`() {
        val fileName = "test.epub"
        val bookFile = File(tempDir, fileName)
        bookFile.writeText("content")
        
        val localPath = downloadManager.getLocalPath("any/path/$fileName")
        assertEquals(bookFile.absolutePath, localPath)
    }
}
