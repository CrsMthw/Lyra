package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.DevicesResponse
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Devices are parsed from JSON strings, as the API delivers them (Gson `Unsafe` allocation). */
class PickLocalDeviceTest {

    private val gson = Gson()
    private val hints = listOf("Galaxy Z Fold8", "SM-F971B")

    private fun devices(json: String): List<SpotifyDevice> =
        gson.fromJson(json, DevicesResponse::class.java).devices

    private fun device(id: String, name: String?, type: String?, active: Boolean = false, restricted: Boolean = false): String {
        val n = name?.let { "\"$it\"" } ?: "null"
        val t = type?.let { "\"$it\"" } ?: "null"
        return """{"id":"$id","name":$n,"type":$t,"is_active":$active,"is_restricted":$restricted}"""
    }

    private fun list(vararg d: String) = devices("""{"devices":[${d.joinToString(",")}]}""")

    // 2026-10-04: was "the active device wins" expecting "pc" — an active Computer is no longer
    // taken for this phone (it sent the wake body to the desktop); the hinted phone is picked.
    @Test
    fun `an active device that is not a handheld and not hinted is not this phone`() {
        val picked = pickLocalDevice(
            list(
                device("phone", "Galaxy Z Fold8", "Smartphone"),
                device("pc", "Desk", "Computer", active = true),
            ),
            hints,
        )
        assertEquals("phone", picked?.id)
        assertNull(pickLocalDevice(list(device("pc", "Desk", "Computer", active = true)), hints))
        assertNull(pickLocalDevice(list(device("bar", "Living Room Soundbar", "Speaker", active = true)), hints))
    }

    // Review 2026-10-04: was "the active handheld wins" expecting "other" — an active handheld may
    // be ANOTHER phone of the account; a handheld NAMED like this phone now comes first.
    @Test
    fun `a handheld named like this phone beats an active unnamed handheld`() {
        val picked = pickLocalDevice(
            list(
                device("phone", "Galaxy Z Fold8", "Smartphone"),
                device("other", "Renamed", "Smartphone", active = true),
            ),
            hints,
        )
        assertEquals("phone", picked?.id)
    }

    @Test
    fun `the active handheld is picked when none is named like this phone`() {
        val picked = pickLocalDevice(
            list(
                device("pixel", "Pixel", "Smartphone"),
                device("other", "Renamed", "Smartphone", active = true),
            ),
            hints,
        )
        assertEquals("other", picked?.id)
    }

    @Test
    fun `an active device of another type is accepted when its name matches a hint`() {
        val picked = pickLocalDevice(
            list(
                device("pc", "Desk", "Computer"),
                device("fold", "Galaxy Z Fold8", "Unknown", active = true),
            ),
            hints,
        )
        assertEquals("fold", picked?.id)
    }

    @Test
    fun `a smartphone named like this phone is picked when nothing is active`() {
        val picked = pickLocalDevice(
            list(
                device("other", "Pixel", "Smartphone"),
                device("phone", "galaxy z fold8", "Smartphone"),
            ),
            hints,
        )
        assertEquals("phone", picked?.id)
    }

    @Test
    fun `the name hint also matches a tablet - an unfolded foldable`() {
        val picked = pickLocalDevice(
            list(
                device("pixel", "Pixel", "Smartphone"),
                device("fold", "SM-F971B", "Tablet"),
            ),
            hints,
        )
        assertEquals("fold", picked?.id)
    }

    @Test
    fun `the only smartphone is picked when no name matches`() {
        val picked = pickLocalDevice(
            list(
                device("pc", "Desk", "Computer"),
                device("phone", "Renamed phone", "Smartphone"),
            ),
            hints,
        )
        assertEquals("phone", picked?.id)
    }

    @Test
    fun `two unnamed smartphones are ambiguous`() {
        assertNull(
            pickLocalDevice(
                list(device("a", "One", "Smartphone"), device("b", "Two", "Smartphone")),
                hints,
            ),
        )
    }

    @Test
    fun `no smartphone at all is null`() {
        assertNull(pickLocalDevice(list(device("pc", "Desk", "Computer")), hints))
        assertNull(pickLocalDevice(emptyList(), hints))
    }

    @Test
    fun `null name and type do not crash and do not match`() {
        val picked = pickLocalDevice(
            list(device("ghost", null, null), device("phone", "Galaxy Z Fold8", "Smartphone")),
            hints,
        )
        assertEquals("phone", picked?.id)
        assertNull(pickLocalDevice(list(device("ghost", null, null)), hints))
    }

    @Test
    fun `devices without an id or restricted are never picked`() {
        val json = """{"devices":[{"id":null,"name":"Galaxy Z Fold8","type":"Smartphone","is_active":true},
            ${device("r", "Galaxy Z Fold8", "Smartphone", active = true, restricted = true)}]}"""
        assertNull(pickLocalDevice(devices(json), hints))
    }

    @Test
    fun `a null slot in the device list is dropped`() {
        val picked = pickLocalDevice(
            devices("""{"devices":[null, ${device("phone", "x", "Smartphone")}]}"""),
            hints,
        )
        assertEquals("phone", picked?.id)
    }

    @Test
    fun `blank hints are ignored`() {
        val picked = pickLocalDevice(
            list(device("a", "One", "Smartphone"), device("b", "", "Smartphone")),
            listOf("", "  "),
        )
        assertNull(picked)
    }

    // ── isNamedLikeThisPhone (2026-10-04): the strict check before playing on this phone ──

    @Test
    fun `a handheld named like this phone is this phone, whatever else is listed`() {
        val (fold, tablet) = list(device("f", "Galaxy Z Fold8", "Smartphone"), device("t", "sm-f971b", "Tablet"))
        assertTrue(isNamedLikeThisPhone(fold, hints))
        assertTrue(isNamedLikeThisPhone(tablet, hints))
    }

    @Test
    fun `the only smartphone is NOT this phone by name`() {
        // pickLocalDevice's rule 3 would take it; the strict check must not (it may be another phone).
        val other = list(device("o", "Pixel", "Smartphone")).single()
        assertEquals("o", pickLocalDevice(listOf(other), hints)?.id)
        assertFalse(isNamedLikeThisPhone(other, hints))
    }

    @Test
    fun `a computer named like a hint, a null name or a missing id is not this phone`() {
        val (pc, ghost) = list(device("pc", "Galaxy Z Fold8", "Computer"), device("g", null, "Smartphone"))
        assertFalse(isNamedLikeThisPhone(pc, hints))
        assertFalse(isNamedLikeThisPhone(ghost, hints))
        val noId = devices("""{"devices":[{"id":null,"name":"Galaxy Z Fold8","type":"Smartphone","is_active":true}]}""").single()
        assertFalse(isNamedLikeThisPhone(noId, hints))
    }

    // ── soleHandheld (2026-10-04): the transfer's last way of recognising this phone ──

    @Test
    fun `the only handheld is found whether it is a smartphone or a tablet`() {
        assertEquals("fold", soleHandheld(list(device("pc", "Desk", "Computer", active = true), device("fold", "x", "Tablet")))?.id)
        assertEquals("p", soleHandheld(list(device("bar", "Bar", "Speaker"), device("p", "y", "Smartphone")))?.id)
    }

    @Test
    fun `two handhelds, none, or only restricted ones give null`() {
        assertNull(soleHandheld(list(device("a", "A", "Smartphone"), device("b", "B", "Tablet"))))
        assertNull(soleHandheld(list(device("pc", "Desk", "Computer"))))
        assertNull(soleHandheld(list(device("r", "R", "Smartphone", restricted = true))))
        assertNull(soleHandheld(list(device("g", "G", null))))
    }
}
