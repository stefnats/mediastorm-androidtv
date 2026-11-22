package com.strmr.tv.ui.settings

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.PlaybackFields
import com.strmr.tv.api.PlaybackSettings
import com.strmr.tv.api.mergePlaybackSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpokenAudioAutoSettingTest {
    private fun obj(value: String) = ApiJson.parseToJsonElement(value).jsonObject
    private fun snapshot(global: Boolean, effective: Boolean, overridden: Boolean, editable: Boolean = true) = spokenAudioAutoSnapshot(
        obj("""{"playback":{"autoCheckAudioLanguage":$global},"userEditableSettings":${if (editable) "[\"playback.autoCheckAudioLanguage\"]" else "[]"}}"""),
        obj("""{"playback":{"autoCheckAudioLanguage":$effective}}"""),
        obj("""{"overriddenPaths":${if (overridden) "[\"playback.autoCheckAudioLanguage\"]" else "[]"}}"""))

    @Test fun oldServerDefaultsOffAndProfileExplicitFalseOverridesInheritedTrue() {
        val old = spokenAudioAutoSnapshot(obj("{}"), obj("{}"), obj("{}"))
        assertFalse(old.supported); assertFalse(old.editable); assertFalse(old.effective)
        assertFalse(PlaybackSettings().autoCheckAudioLanguage)
        val inherited = snapshot(true, true, false)
        assertTrue(inherited.effective); assertNull(inherited.overrideValue); assertEquals(0, inherited.selected)
        val explicitFalse = snapshot(true, false, true)
        assertEquals(false, explicitFalse.overrideValue); assertFalse(explicitFalse.effective); assertEquals(1, explicitFalse.selected)
        assertFalse(snapshot(false, false, false, editable = false).editable)
    }

    @Test fun devicePreferenceCannotEnableOrDisableTheProfileOnlySetting() {
        assertFalse(mergePlaybackSettings(PlaybackFields(autoCheckAudioLanguage = false),
            PlaybackFields(autoCheckAudioLanguage = true)).autoCheckAudioLanguage)
        assertTrue(mergePlaybackSettings(PlaybackFields(autoCheckAudioLanguage = true),
            PlaybackFields(autoCheckAudioLanguage = false)).autoCheckAudioLanguage)
        assertFalse(mergePlaybackSettings(null, PlaybackFields(autoCheckAudioLanguage = true)).autoCheckAudioLanguage)
    }

    @Test fun singlePathPatchDistinguishesFalseFromInheritanceWithoutWholeSettingsWrite() {
        assertEquals(obj("""{"path":"playback.autoCheckAudioLanguage","value":false}"""), spokenAudioAutoPatch(false))
        assertEquals(obj("""{"path":"playback.autoCheckAudioLanguage","value":true}"""), spokenAudioAutoPatch(true))
        assertEquals(obj("""{"path":"playback.autoCheckAudioLanguage","reset":true}"""), spokenAudioAutoPatch(null))
    }

    private class Gateway : SpokenAudioAutoGateway {
        var current = true
        var snapshot = SpokenAudioAutoSnapshot(true, true, null, true, true)
        var writes = 0
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        override fun isCurrent() = current
        override suspend fun read(): SpokenAudioAutoSnapshot {
            gate?.await()
            return snapshot
        }
        override suspend fun write(value: Boolean?): SpokenAudioAutoSnapshot {
            writes++
            if (failWrite) error("Synthetic lost acknowledgement")
            gate?.await()
            snapshot = snapshot.copy(overrideValue = value, effective = value ?: snapshot.inherited)
            return snapshot
        }
    }

    @Test fun explicitFalseResetAndManagedPermissionFollowConfirmedSnapshots() = runTest {
        val gateway = Gateway(); val editor = SpokenAudioAutoEditor(gateway)
        editor.load(); editor.save(false)
        assertEquals(false, editor.state.value.snapshot!!.overrideValue)
        assertFalse(editor.state.value.snapshot!!.effective)
        editor.save(null); assertTrue(editor.state.value.snapshot!!.effective)
        assertNull(editor.state.value.snapshot!!.overrideValue); assertEquals(2, gateway.writes)
        gateway.snapshot = gateway.snapshot.copy(editable = false)
        editor.load(); editor.save(true); assertEquals(2, gateway.writes)
        editor.close()
    }

    @Test fun ambiguousWriteRequiresExplicitReloadAndNeverAutomaticallyRetries() = runTest {
        val gateway = Gateway(); val editor = SpokenAudioAutoEditor(gateway)
        editor.load(); gateway.failWrite = true
        editor.save(false); assertEquals(1, gateway.writes)
        assertNotNull(editor.state.value.error); assertNull(editor.state.value.snapshot!!.overrideValue)
        editor.save(false); assertEquals(1, gateway.writes)
        gateway.failWrite = false; editor.load(); editor.save(false)
        assertEquals(2, gateway.writes); assertEquals(false, editor.state.value.snapshot!!.overrideValue)
        editor.close()
    }

    @Test fun changedProfileOrClosedEditorCannotPublishLateReadback() = runTest {
        for (kind in listOf("profile", "close")) {
            val gateway = Gateway(); val editor = SpokenAudioAutoEditor(gateway)
            editor.load(); gateway.gate = CompletableDeferred()
            val saving = launch { editor.save(false) }; runCurrent()
            assertEquals(1, gateway.writes)
            if (kind == "profile") gateway.current = false else editor.close()
            gateway.gate!!.complete(Unit); advanceUntilIdle()
            assertTrue(saving.isCancelled)
            assertNull(editor.state.value.snapshot!!.overrideValue)
            editor.close()
        }
    }
}
