package dev.immich.picker

import android.app.Application
import dev.immich.picker.data.AppPreferences
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.LauncherIcon

class ImmichPickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ImmichService.initialize(this)

        // Re-assert the launcher-icon state for every process start, not just when the
        // Settings screen happens to be built. The provider and picker run in the same
        // process, but they can start without MainActivity ever being created, so this is
        // the only place that covers every entry point. An app update can also reset
        // component enabled flags, and the stored preference is the source of truth.
        LauncherIcon.sync(this, AppPreferences(this))
    }
}
