package com.strmr.tv.app

import com.strmr.tv.api.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSelectionPolicyTest {
    private val adult = Profile(id = "adult", name = "Adult", hasPin = true)
    private val child = Profile(id = "child", name = "Child", hasPin = false)

    @Test fun `an automatic PIN-protected profile requires the existing picker`() {
        assertNull(ProfileSelectionPolicy.startup(listOf(adult, child), adult.id, null))
        assertEquals(child, ProfileSelectionPolicy.startup(listOf(adult, child), child.id, null))
    }

    @Test fun `a home screen row link only opens PIN-free profiles`() {
        assertNull(ProfileSelectionPolicy.startup(listOf(adult, child), null, adult.id))
        assertEquals(child, ProfileSelectionPolicy.startup(listOf(adult, child), adult.id, child.id))
        assertNull(ProfileSelectionPolicy.startup(listOf(adult, child), adult.id, adult.id))
    }

    @Test fun `only profiles returned for the current account can open`() {
        assertNull(ProfileSelectionPolicy.startup(listOf(adult, child), "other-account-profile", null))
        assertNull(ProfileSelectionPolicy.startup(listOf(adult, child), null, "other-account-profile"))
    }

    @Test fun `a single profile opens by itself only without a PIN`() {
        assertEquals(child, ProfileSelectionPolicy.startup(listOf(child), null, null))
        assertNull(ProfileSelectionPolicy.startup(listOf(adult), null, null))
    }
    @Test fun `PIN-free linked profile keeps priority over a protected automatic choice`() {
        assertEquals(child, ProfileSelectionPolicy.startup(listOf(adult, child), adult.id, child.id))
        assertEquals(child, ProfileSelectionPolicy.startup(listOf(adult, child), child.id, null))
    }

    @Test fun `PIN flag is preserved independently of kids or adult classification`() {
        for (kids in listOf(true, false, null)) {
            val locked = adult.copy(isKidsProfile = kids)
            assertNull(ProfileSelectionPolicy.startup(listOf(locked), locked.id, null))
            assertNull(ProfileSelectionPolicy.startup(listOf(locked), locked.id, locked.id))
        }
    }

}
