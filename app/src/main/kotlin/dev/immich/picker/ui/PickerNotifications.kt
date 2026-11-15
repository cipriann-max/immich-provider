package dev.immich.picker.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.immich.picker.R
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.VersionAssessment

/**
 * User-visible signals for problems that would otherwise be silent, because they
 * happen while the user is inside *another* app's file picker.
 *
 * Three cases warrant a notification:
 *  - the stored token/API key was rejected (tapping opens the login screen)
 *  - the server is unreachable (informational, retried automatically)
 *  - the server speaks a newer schema than this build understands
 */
object PickerNotifications {

    private const val TAG = "PickerNotifications"
    private const val CHANNEL_ERRORS = "immich_picker_errors"
    private const val CHANNEL_VERSION = "immich_picker_version"
    private const val ID_AUTH = 1001
    private const val ID_NETWORK = 1002
    private const val ID_INCOMPATIBLE = 1003
    private const val ID_VERSION = 1004

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ERRORS,
                context.getString(R.string.notif_channel_errors),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_errors_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VERSION,
                context.getString(R.string.notif_channel_version),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_version_desc)
            },
        )
    }

    fun notifyAuthExpired(context: Context, message: String?) {
        notify(
            context = context,
            id = ID_AUTH,
            channel = CHANNEL_ERRORS,
            title = context.getString(R.string.notif_auth_title),
            text = message ?: context.getString(R.string.notif_auth_text),
            openSettings = true,
        )
    }

    fun notifyUnreachable(context: Context, message: String?) {
        notify(
            context = context,
            id = ID_NETWORK,
            channel = CHANNEL_ERRORS,
            title = context.getString(R.string.notif_network_title),
            text = message ?: context.getString(R.string.notif_network_text),
            openSettings = true,
        )
    }

    fun notifyIncompatible(context: Context, detail: String?) {
        notify(
            context = context,
            id = ID_INCOMPATIBLE,
            channel = CHANNEL_ERRORS,
            title = context.getString(R.string.notif_incompatible_title),
            text = detail ?: "The Immich server returned data this app does not understand.",
            openSettings = true,
        )
    }

    fun notifyVersionWarning(context: Context, assessment: VersionAssessment) {
        if (!assessment.isWarning) {
            cancel(context, ID_VERSION)
            return
        }
        val text = assessment.message ?: return
        notify(
            context = context,
            id = ID_VERSION,
            channel = CHANNEL_VERSION,
            title = context.getString(R.string.notif_version_title),
            text = text,
            openSettings = true,
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        openSettings: Boolean,
    ) {
        if (!canPostNotifications(context)) {
            Log.i(TAG, "notifications not permitted; dropping '$title'")
            return
        }
        ensureChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS, openSettings)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_immich)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }.onFailure { Log.w(TAG, "could not post notification", it) }
    }

    fun cancel(context: Context, id: Int = -1) {
        val manager = NotificationManagerCompat.from(context)
        if (id == -1) {
            manager.cancelAll()
        } else {
            manager.cancel(id)
        }
    }

    fun cancelAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Routes the last recorded [ImmichService.ServiceError] to the right notification. */
    fun mirrorServiceError(context: Context) {
        when (val error = ImmichService.lastError()) {
            is ImmichService.ServiceError.AuthExpired -> notifyAuthExpired(context, error.message)
            is ImmichService.ServiceError.Network -> notifyUnreachable(context, error.message)
            is ImmichService.ServiceError.Incompatible -> notifyIncompatible(context, error.message)
            null -> Unit
        }
    }
}
