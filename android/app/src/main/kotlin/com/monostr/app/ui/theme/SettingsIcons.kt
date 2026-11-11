package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Line icons of the settings overview, after Feather Icons (feathericons.com, MIT), built like [TabIcons]. Circles, lines and
 * polylines of the SVGs are written as path data. Bell and magnifier are [TabIcons.Bell] and [TabIcons.Search].
 */
object SettingsIcons {
    private const val CIRCLE_12 = "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12Z"

    val Relays: ImageVector by lazy {
        featherIcon(
            "settings_relays",
            listOf(
                "M16.24 7.76a6 6 0 0 1 0 8.49M7.76 16.24a6 6 0 0 1 0-8.49M19.07 4.93a10 10 0 0 1 0 14.14M4.93 19.07a10 10 0 0 1 0-14.14" to false,
                "M14 12A2 2 0 1 1 10 12A2 2 0 1 1 14 12Z" to false,
            ),
        )
    }
    val Image: ImageVector by lazy {
        featherIcon(
            "settings_image",
            listOf(
                "M5 3H19A2 2 0 0 1 21 5V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3Z" to false,
                "M10 8.5A1.5 1.5 0 1 1 7 8.5A1.5 1.5 0 1 1 10 8.5Z" to false,
                "M21 15L16 10L5 21" to false,
            ),
        )
    }
    val Globe: ImageVector by lazy {
        featherIcon(
            "settings_globe",
            listOf(
                CIRCLE_12 to false,
                "M2 12H22" to false,
                "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" to false,
            ),
        )
    }
    val Sun: ImageVector by lazy {
        featherIcon(
            "settings_sun",
            listOf(
                "M17 12A5 5 0 1 1 7 12A5 5 0 1 1 17 12Z" to false,
                "M12 1V3M12 21V23M4.22 4.22L5.64 5.64M18.36 18.36L19.78 19.78M1 12H3M21 12H23M4.22 19.78L5.64 18.36M18.36 5.64L19.78 4.22" to false,
            ),
        )
    }
    val Help: ImageVector by lazy {
        featherIcon(
            "settings_help",
            listOf(CIRCLE_12 to false, "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" to false, "M12 17H12.01" to false),
        )
    }
    val LogOut: ImageVector by lazy {
        featherIcon(
            "settings_log_out",
            listOf("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" to false, "M16 17L21 12L16 7" to false, "M21 12H9" to false),
        )
    }
    val Chevron: ImageVector by lazy { featherIcon("settings_chevron", listOf("M9 18L15 12L9 6" to false)) }
    val Check: ImageVector by lazy { featherIcon("settings_check", listOf("M20 6L9 17L4 12" to false)) }
    val Trash: ImageVector by lazy {
        featherIcon(
            "settings_trash",
            listOf(
                "M3 6H5H21" to false,
                "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" to false,
                "M10 11V17M14 11V17" to false,
            ),
        )
    }
    val Key: ImageVector by lazy {
        featherIcon(
            "settings_key",
            listOf("M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" to false),
        )
    }
    val Copy: ImageVector by lazy {
        featherIcon(
            "settings_copy",
            listOf(
                "M11 9H20A2 2 0 0 1 22 11V20A2 2 0 0 1 20 22H11A2 2 0 0 1 9 20V11A2 2 0 0 1 11 9Z" to false,
                "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" to false,
            ),
        )
    }
    val Info: ImageVector by lazy {
        featherIcon("settings_info", listOf(CIRCLE_12 to false, "M12 16V12M12 8H12.01" to false))
    }
}
