package com.strmr.tv.app

import com.strmr.tv.api.Profile

/**
 * Which profile opens at launch without the picker.
 *
 * "Use this profile automatically" restores only PIN-free profiles. A protected
 * profile always uses the existing picker/PIN flow at startup, even if its ID or
 * a previous unlock was saved. Home screen links also open only PIN-free profiles.
 * IDs not in the signed-in account's profile list (stale or foreign) never open.
 */
internal object ProfileSelectionPolicy {
    fun startup(profiles: List<Profile>, automaticId: String?, linkedId: String?): Profile? =
        profiles.firstOrNull { it.id == linkedId && !it.requiresPin }
            ?: profiles.firstOrNull { it.id == automaticId && !it.requiresPin }
            ?: profiles.singleOrNull()?.takeIf { !it.requiresPin }
}
