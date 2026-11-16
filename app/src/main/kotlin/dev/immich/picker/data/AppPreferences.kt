package dev.immich.picker.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Plain, non-secret user preferences.
 *
 * Kept out of [CredentialStore] on purpose: that file is encrypted behind an Android
 * Keystore master key, and a UI toggle has no business depending on the Keystore being
 * available. Losing this file costs nothing but a reset to defaults.
 */
class AppPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Whether the launcher icon is shown. Defaults to true: a new install is visible. */
    var showLauncherIcon: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LAUNCHER_ICON, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_LAUNCHER_ICON, value).apply()
        }

    companion object {
        private const val FILE_NAME = "immich_picker_prefs"
        private const val KEY_SHOW_LAUNCHER_ICON = "show_launcher_icon"
    }
}

/**
 * Applies the launcher-icon preference to the [android.app.Activity] alias in the manifest.
 *
 * Hiding the icon means disabling the `activity-alias` that holds the LAUNCHER intent
 * filter. That is the only supported approach: an app cannot remove its own icon any other
 * way, and it is reversible on the next launch because the choice is also persisted.
 *
 * `MainActivity` is deliberately left enabled, so the cog in the picker still opens
 * Settings while the icon is hidden. If the alias were the only entry point, hiding it
 * would make Settings unreachable.
 */
object LauncherIcon {

    /** Component id of the alias declared in AndroidManifest.xml. */
    private const val ALIAS_CLASS = "dev.immich.picker.ui.LauncherAlias"

    fun apply(context: Context, visible: Boolean) {
        val component = ComponentName(context.packageName, ALIAS_CLASS)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                // Persist across reboots; the value is re-applied on every launch anyway,
                // which also repairs a package-manager reset (e.g. after an app update
                // that changed the component set).
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /** Re-applies the stored preference, so the icon state survives updates. */
    fun sync(context: Context, preferences: AppPreferences) {
        apply(context, preferences.showLauncherIcon)
    }
}
