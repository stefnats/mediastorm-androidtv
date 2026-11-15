package com.strmr.mobile.ui.reminders

import com.strmr.mobile.ui.shell.Route
import com.strmr.tv.api.DetailsTarget
import org.junit.Assert.*
import org.junit.Test

class ReminderNavigationTest {
    @Test fun `historical inbox navigation avoids prewarm and autoplay while normal navigation keeps its default`() {
        val title=DetailsTarget("tmdb:movie:123","Synthetic",true)
        assertTrue(Route.Details(title).prewarmOnOpen)
        val route=reminderRoute(title)
        assertEquals(title,route.target)
        assertFalse(route.autoplay)
        assertFalse(route.prewarmOnOpen)
    }
}
