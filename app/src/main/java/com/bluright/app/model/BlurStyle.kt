package com.bluright.app.model

/**
 * Visual privacy mask styles available for preview blurring.
 */
enum class BlurStyle(val displayName: String, val description: String) {
    PIXELATE(
        displayName = "Mosaic",
        description = "High-density digital pixelation grid"
    ),
    FROSTED(
        displayName = "Frosted",
        description = "Multi-pass translucent frosted glass privacy mask"
    ),
    SOLID_SHIELD(
        displayName = "Shield",
        description = "Solid cyber privacy shield with lock crest"
    )
}
