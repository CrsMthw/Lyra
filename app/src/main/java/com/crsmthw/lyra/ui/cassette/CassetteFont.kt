package com.crsmthw.lyra.ui.cassette

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.crsmthw.lyra.R

/**
 * Playfair Display (SIL Open Font License 1.1, `assets/fonts/LICENSE-PlayfairDisplay.txt`) — the
 * label's serif, bundled as ONE variable font (`res/font/playfair_display.ttf`, ~300 KB) so the
 * cassette looks the same on every OEM's system serif. Only the label's two big lines use it; the
 * small print stays on the theme's sans. App stays MIT — the OFL covers only the font file.
 */
val CassetteSerif: FontFamily = FontFamily(
    Font(R.font.playfair_display, FontWeight.Normal,   variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.playfair_display, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.playfair_display, FontWeight.Bold,     variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)
