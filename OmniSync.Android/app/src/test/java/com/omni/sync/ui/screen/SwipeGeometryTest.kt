package com.omni.sync.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGeometryTest {
    @Test fun qwertyCoordinatesMatchFutoEnglishModel() {
        assertEquals(0.05f, SwipeGeometry.center('q')!!.first, 0.0001f)
        assertEquals(0.95f, SwipeGeometry.center('p')!!.first, 0.0001f)
        assertEquals(0.10f, SwipeGeometry.center('a')!!.first, 0.0001f)
        assertEquals(0.20f, SwipeGeometry.center('z')!!.first, 0.0001f)
        assertEquals(5f / 6f, SwipeGeometry.center('m')!!.second, 0.0001f)
        assertEquals(null, SwipeGeometry.center('æ'))
    }

    @Test fun tapsStayTapsAndSwipesAreDetected() {
        val tap = listOf(
            SwipePoint(0.25f, 0.167f, 0f),
            SwipePoint(0.251f, 0.168f, 24f),
            SwipePoint(0.256f, 0.17f, 50f)
        )
        assertFalse(SwipeGeometry.isSwipe(tap))
        assertEquals('e', SwipeGeometry.keyAt(0.25f, 0.167f))
        assertEquals(null, SwipeGeometry.keyAt(0.00f, 0.00f))
        val swipe = listOf(
            SwipePoint(0.45f, 0.167f, 0f), // t
            SwipePoint(0.60f, 0.50f, 110f), // h
            SwipePoint(0.25f, 0.167f, 240f) // e
        )
        assertTrue(SwipeGeometry.isSwipe(swipe))
    }

    @Test fun oneKeyDragIsNotSwiping() {
        val samples = listOf(
            SwipePoint(.505f, .5f, 0f),
            SwipePoint(.512f, .50f, 60f),
            SwipePoint(.509f, .51f, 100f)
        )
        assertFalse(SwipeGeometry.isSwipe(samples))
    }
}
