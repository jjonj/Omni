package com.omni.sync.viewmodel

import com.omni.sync.data.repository.SignalRClient
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

@OptIn(ExperimentalCoroutinesApi::class)
class BooksViewModelTest {

    @Mock
    private lateinit var signalRClient: SignalRClient
    private lateinit var viewModel: BooksViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        viewModel = BooksViewModel(signalRClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test initial state is Idle`() {
        assertTrue(viewModel.libraryState.value is LibraryState.Idle)
    }

    @Test
    fun `test scanLibrary fails when not implemented`() = runTest {
        viewModel.scanLibrary()
        assertTrue(viewModel.libraryState.value is LibraryState.Error)
        assertEquals("Not implemented yet", (viewModel.libraryState.value as LibraryState.Error).message)
    }
}
