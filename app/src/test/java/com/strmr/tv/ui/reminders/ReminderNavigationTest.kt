package com.strmr.tv.ui.reminders

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.ui.Navigator
import com.strmr.tv.ui.Page
import org.junit.Assert.*
import org.junit.Test

class ReminderNavigationTest {
    @Test fun `inbox title goes through direct page navigation without selecting autoplay`() {
        val title=DetailsTarget("tmdb:movie:123","Synthetic",true)
        var opened: Page?=null
        val navigator=Navigator(push={opened=it})
        navigator.open(reminderPage(title))
        val page=opened as Page.Details
        assertEquals(title,page.target)
        assertFalse(page.autoplay)
    }
}
