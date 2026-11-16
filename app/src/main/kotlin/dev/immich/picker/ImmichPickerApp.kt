package dev.immich.picker

import android.app.Application
import dev.immich.picker.data.ImmichService

class ImmichPickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ImmichService.initialize(this)
    }
}
