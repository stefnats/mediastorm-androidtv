package com.strmr.tv.ui.settings

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackQualityEditorTest {
    private class Gateway : PlaybackQualityGateway {
        var snapshot = PlaybackQualitySnapshot(
            server = mapOf("minResolution" to "720p", "maxResolution" to "2160p"),
            profile = mapOf("minResolution" to "1080p", "maxResolution" to "2160p"),
            device = emptyMap(), profileOverrides = setOf("filtering.minResolution"),
            editable = QualityField.entries.map { it.path }.toSet(),
        )
        val writes = mutableListOf<Triple<QualityScope, QualityField, String?>>()
        var failRead = false
        var failAcknowledgement = false
        var hold: CompletableDeferred<Unit>? = null
        override fun isCurrent() = true
        override suspend fun read(): PlaybackQualitySnapshot {
            if (failRead) throw IOException("Synthetic read failure")
            return snapshot
        }
        override suspend fun write(scope: QualityScope, field: QualityField, value: String?): PlaybackQualitySnapshot {
            writes += Triple(scope, field, value)
            hold?.await()
            snapshot = if (scope == QualityScope.DEVICE) snapshot.copy(device =
                if (value == null) snapshot.device - field.key else snapshot.device + (field.key to value))
            else snapshot.copy(
                profile = snapshot.profile + (field.key to (value ?: snapshot.server[field.key].orEmpty())),
                profileOverrides = if (value == null) snapshot.profileOverrides - field.path else snapshot.profileOverrides + field.path,
            )
            if (failAcknowledgement) throw IOException("Synthetic lost acknowledgement")
            return snapshot
        }
    }

    @Test fun inheritanceAndAnExplicitEmptyMinimumRemainDistinct() = runTest {
        val gateway = Gateway()
        val editor = PlaybackQualityEditor(gateway)
        editor.load()
        val initial = editor.state.value.snapshot!!
        assertEquals("1080p", initial.row(QualityScope.PROFILE, QualityField.MINIMUM).overrideValue)
        assertNull(initial.row(QualityScope.DEVICE, QualityField.MINIMUM).overrideValue)
        assertTrue(initial.row(QualityScope.DEVICE, QualityField.MINIMUM).valueLabel.contains("profile (1080p)"))
        editor.save(QualityScope.DEVICE, QualityField.MINIMUM, "")
        val cleared = editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM)
        assertEquals("", cleared.overrideValue)
        assertEquals("No minimum", cleared.valueLabel)
        editor.save(QualityScope.DEVICE, QualityField.MINIMUM, null)
        assertNull(editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM).overrideValue)
        assertTrue(editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM).valueLabel.contains("1080p"))
    }

    @Test fun changingMinimumPreservesMaximumAndResetUsesInheritance() = runTest {
        val gateway = Gateway()
        val editor = PlaybackQualityEditor(gateway)
        editor.load(); editor.save(QualityScope.PROFILE, QualityField.MINIMUM, "720p")
        assertEquals("720p", editor.state.value.snapshot!!.row(QualityScope.PROFILE, QualityField.MINIMUM).overrideValue)
        assertEquals("2160p", editor.state.value.snapshot!!.profile["maxResolution"])
        assertEquals("2160p", editor.state.value.snapshot!!.server["maxResolution"])
        editor.save(QualityScope.PROFILE, QualityField.MINIMUM, null)
        val inherited = editor.state.value.snapshot!!.row(QualityScope.PROFILE, QualityField.MINIMUM)
        assertNull(inherited.overrideValue)
        assertTrue(inherited.valueLabel.contains("Follow server (720p)"))
        val emptyPatch = qualitySettingPatch(QualityField.MINIMUM, "")
        assertEquals(setOf("path", "value"), emptyPatch.keys)
        assertEquals("filtering.minResolution", emptyPatch["path"]!!.jsonPrimitive.content)
        assertEquals("", emptyPatch["value"]!!.jsonPrimitive.content)
        val inheritedPatch = qualitySettingPatch(QualityField.MINIMUM, null)
        assertEquals(setOf("path", "reset"), inheritedPatch.keys)
        assertTrue(inheritedPatch["reset"]!!.jsonPrimitive.boolean)
    }

    @Test fun unknownSaveResultRequiresReloadAndKeepsLastConfirmedDisplay() = runTest {
        val gateway = Gateway()
        val editor = PlaybackQualityEditor(gateway)
        editor.load(); gateway.failAcknowledgement = true
        editor.save(QualityScope.DEVICE, QualityField.MINIMUM, "")
        assertNotNull(editor.state.value.error)
        assertNull(editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM).overrideValue)
        editor.save(QualityScope.DEVICE, QualityField.MINIMUM, "720p")
        assertEquals(1, gateway.writes.size)
        gateway.failAcknowledgement = false; editor.load()
        assertNull(editor.state.value.error)
        assertEquals("", editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM).overrideValue)
    }

    @Test fun serverManagedBoundsAndUnavailableSettingsDoNotWrite() = runTest {
        val gateway = Gateway().apply { snapshot = snapshot.copy(editable = emptySet()) }
        val editor = PlaybackQualityEditor(gateway)
        editor.load(); editor.save(QualityScope.PROFILE, QualityField.MINIMUM, "720p")
        assertTrue(gateway.writes.isEmpty())
        assertTrue(editor.state.value.snapshot!!.rows.none { it.editable })
        assertTrue(editor.state.value.snapshot!!.rows.all { it.description.contains("Managed by the server") })
        gateway.failRead = true; editor.load(); editor.save(QualityScope.DEVICE, QualityField.MAXIMUM, "1080p")
        assertNull(editor.state.value.snapshot)
        assertNotNull(editor.state.value.error)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun heldSaveRejectsOverlappingChangesUntilConfirmation() = runTest {
        val gateway = Gateway().apply { hold = CompletableDeferred() }
        val editor = PlaybackQualityEditor(gateway)
        editor.load()
        val save = launch { editor.save(QualityScope.DEVICE, QualityField.MINIMUM, "720p") }
        runCurrent()
        editor.save(QualityScope.DEVICE, QualityField.MAXIMUM, "1080p")
        assertEquals(1, gateway.writes.size)
        assertTrue(editor.state.value.saving)
        assertNull(editor.state.value.snapshot!!.row(QualityScope.DEVICE, QualityField.MINIMUM).overrideValue)
        gateway.hold!!.complete(Unit); save.join()
        assertFalse(editor.state.value.saving)
        assertEquals("720p", editor.state.value.snapshot!!.device["minResolution"])
        assertEquals("2160p", editor.state.value.snapshot!!.profile["maxResolution"])
    }

    @Test fun storedIntermediateTiersStaySelectedWithoutBeingRewritten() = runTest {
        for (value in listOf("576p", "1440p", "4320p")) {
            for (scope in QualityScope.entries) {
                for (field in QualityField.entries) {
                    val gateway = Gateway().apply {
                        snapshot = snapshot.copy(profile = snapshot.profile + (field.key to value),
                            device = snapshot.device + (field.key to value), profileOverrides = snapshot.profileOverrides + field.path)
                    }
                    val editor = PlaybackQualityEditor(gateway)
                    editor.load()
                    val row = editor.state.value.snapshot!!.row(scope, field)
                    assertTrue(row.selected > 0)
                    assertEquals(value, row.choices[row.selected].value)
                    assertEquals(value, row.valueLabel)
                    editor.save(scope, field, row.choices[row.selected].value)
                    assertTrue(gateway.writes.isEmpty())
                    assertEquals(value, editor.state.value.snapshot!!.row(scope, field).overrideValue)
                }
            }
        }
    }

    @Test fun managedProfileDisplaysEffectiveQualityWithoutInventingInheritance() = runTest {
        val gateway = Gateway().apply { snapshot = snapshot.copy(editable = emptySet(), profileOverrides = emptySet()) }
        val editor = PlaybackQualityEditor(gateway)
        editor.load()
        val snapshot = editor.state.value.snapshot!!
        val profile = snapshot.row(QualityScope.PROFILE, QualityField.MINIMUM)
        assertEquals("720p", snapshot.server["minResolution"])
        assertEquals("1080p", profile.valueLabel)
        assertFalse(profile.editable)
        assertFalse(profile.valueLabel.contains("Follow server"))
        assertNull(profile.overrideValue)
        val device = snapshot.row(QualityScope.DEVICE, QualityField.MINIMUM)
        assertNull(device.overrideValue)
        assertEquals("Follow profile (1080p)", device.valueLabel)
        editor.save(QualityScope.PROFILE, QualityField.MINIMUM, "720p")
        assertTrue(gateway.writes.isEmpty())
    }
}
