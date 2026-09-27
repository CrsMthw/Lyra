package com.crsmthw.lyra.data.player

import com.crsmthw.lyra.data.remote.model.DevicesResponse
import com.crsmthw.lyra.data.remote.model.SpotifyDevice
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `the active device wins`() {
        val picked = pickLocalDevice(
            list(
                device("phone", "Galaxy Z Fold8", "Smartphone"),
                device("pc", "Desk", "Computer", active = true),
            ),
            hints,
        )
        assertEquals("pc", picked?.id)
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
}
