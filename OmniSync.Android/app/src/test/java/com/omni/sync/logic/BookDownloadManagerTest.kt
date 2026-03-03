package com.omni.sync.logic

import android.content.Context
import com.omni.sync.data.model.FileSystemEntry
import com.omni.sync.data.repository.SignalRClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private lateinit var signalRClient: SignalRClient
    
    private lateinit var downloadManager: BookDownloadManager

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        `when`(context.filesDir).thenReturn(tempDir)
        downloadManager = BookDownloadManager(context, signalRClient)
    }

    @Test
    fun `test downloadBook sets initial status and then error when not implemented`() = runTest {
        val entry = FileSystemEntry("test.epub", "path/to/test.epub", false, 1024, Date())
        
        downloadManager.downloadBook(entry)
        
        // Use a small delay to allow the coroutine to run
        kotlinx.coroutines.delay(100)
        
        val status = downloadManager.downloadStatuses.value[entry.path]
        assertNotNull(status)
        assertEquals("Not implemented yet", status?.error)
    }
}
