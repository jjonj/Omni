package com.omni.sync.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedTextActionsTest {
    @Test fun allowsAbsoluteWebUrls() {
        assertEquals("https://example.com/path?q=1", SelectedTextActions.webUrlOrNull("  https://example.com/path?q=1  "))
        assertEquals("HTTP://localhost:3333/gateway/", SelectedTextActions.webUrlOrNull("HTTP://localhost:3333/gateway/"))
    }

    @Test fun rejectsOrdinaryTextAndNonWebSchemes() {
        listOf("hello world", "example.com", "ftp://example.com/file", "javascript:alert(1)",
            "https://", "https://example.com/other path", "https://example.com\\nmore text", "")
            .forEach { assertNull("Must not navigate to: $it", SelectedTextActions.webUrlOrNull(it)) }
    }
}
