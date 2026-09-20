package com.compvdo.app.data

/**
 * Light / dark preference — implements R12.4.
 *
 * The theme followed the system with no way to override it, which is fine until
 * someone wants the app light while the phone is dark. [SYSTEM] stays the
 * default, so nothing changes for anyone who never opens Settings.
 */
enum class ThemeSetting(val label: String, val description: String) {
    SYSTEM("Follow system", "Match the phone's light/dark setting"),
    LIGHT("Light", "Always light, whatever the phone is set to"),
    DARK("Dark", "Always dark, whatever the phone is set to");

    companion object {
        val DEFAULT = SYSTEM
    }
}
