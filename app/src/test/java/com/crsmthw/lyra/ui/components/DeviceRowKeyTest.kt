package com.crsmthw.lyra.ui.components

import com.crsmthw.lyra.data.remote.model.DevicesResponse
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Two id-less devices with the same generic name must still get distinct LazyColumn keys (audit 2026-10-04 C6). */
class DeviceRowKeyTest {
    @Test
    fun `id-less same-name devices get distinct keys that never equal the fixed ones`() {
        val devices = Gson().fromJson(
            """{"devices":[{"id":null,"name":"Speaker","type":"Speaker","is_active":false},
                           {"id":null,"name":"Speaker","type":"Speaker","is_active":false},
                           {"id":null,"name":"volume","type":"Speaker","is_active":false}]}""",
            DevicesResponse::class.java,
        ).devices
        val keys = devices.mapIndexed { i, d -> deviceRowKey(d, i) }
        assertEquals(keys.size, keys.toSet().size)
        for (fixed in listOf("this_device_card", "volume", "empty")) assertFalse(fixed in keys)
    }
}
