package com.crsmthw.lyra.ui.ilyra.lcd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** What the LCD's battery glyph draws. */
data class BatteryState(val percent: Int, val isCharging: Boolean)

/** The real battery, live: the sticky ACTION_BATTERY_CHANGED broadcast for as long as we compose. */
@Composable
fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readBattery(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                intent.toBatteryState()?.let { state = it }
            }
        }
        val sticky = context.registerReceiver(
            receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED,
        )
        sticky?.toBatteryState()?.let { state = it }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

private fun Intent.toBatteryState(): BatteryState? {
    val level  = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale  = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    if (level < 0 || scale <= 0) return null
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryState((level * 100 / scale).coerceIn(0, 100), charging)
}

private fun readBattery(context: Context): BatteryState {
    val bm = context.getSystemService(BatteryManager::class.java)
    val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
    return BatteryState(pct, bm?.isCharging == true)
}
