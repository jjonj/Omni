package com.omni.sync.viewmodel

import com.omni.sync.data.repository.SignalRClient
import com.omni.sync.utils.BookType
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class BooksViewModelTest {

    @Mock
    private lateinit var signalRClient: SignalRClient
    @Mock
    private lateinit var downloadManager: com.omni.sync.logic.BookDownloadManager
    private lateinit var viewModel: BooksViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setComputationSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setNewThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        
        viewModel = BooksViewModel(signalRClient, downloadManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
    }

    @Test
    fun `test initial state is Idle`() {
        assertTrue(viewModel.libraryState.value is LibraryState.Idle)
    }

    @Test
    fun `test scanLibrary success`() = runTest {
        val rootPath = "B:\\GDrive\\Books"
        val mockEntries = listOf(
            com.omni.sync.data.model.FileSystemEntry(
                name = "Test.epub",
                path = "B:\\GDrive\\Books\\Books\\Fiction\\Test.epub",
                isDirectory = false,
                entryType = "File",
                size = 1024,
                lastModified = Date()
            ),
            com.omni.sync.data.model.FileSystemEntry(
                name = "Three Body",
                path = "B:\\GDrive\\Books\\Audiobooks\\Fiction\\Three Body",
                isDirectory = true,
                entryType = "AudiobookFolder",
                size = 5000,
                lastModified = Date()
            )
        )

        `when`(signalRClient.scanBooks(rootPath)).thenReturn(io.reactivex.rxjava3.core.Single.just(mockEntries))

        viewModel.scanLibrary(rootPath)

        assertTrue(viewModel.libraryState.value is LibraryState.Success)
        val books = (viewModel.libraryState.value as LibraryState.Success).books
        assertEquals(2, books.size)
        assertEquals("Fiction", books[0].category)
        assertEquals("Fiction", books[1].category)
        assertEquals(BookType.EPUB, books[0].type)
        assertEquals(BookType.AUDIOBOOK, books[1].type)
    }

    @Test
    fun `test saveProgress success`() = runTest {
        val path = "B:\\Books\\test.epub"
        val pos = "123"
        `when`(signalRClient.saveBookProgress(path, pos)).thenReturn(Unit)

        viewModel.saveProgress(path, pos)
        
        org.mockito.Mockito.verify(signalRClient).saveBookProgress(path, pos)
    }
}
