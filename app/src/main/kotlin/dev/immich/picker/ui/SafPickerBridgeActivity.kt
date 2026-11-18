package dev.immich.picker.ui

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import dev.immich.picker.BuildConfig
import dev.immich.picker.data.ImmichService

/**
 * Makes the app discoverable in pickers that list *apps* rather than SAF roots.
 *
 * ## Why this exists
 *
 * There are two kinds of chooser on modern Android:
 *
 *  - **DocumentsUI** lists **provider roots**. Our root appears there for both
 *    `ACTION_OPEN_DOCUMENT` and `ACTION_GET_CONTENT`, with no activity needed.
 *  - **The system photo picker** (`com.google.android.photopicker`) lists **apps** that
 *    answer the intent. It shows no SAF roots at all, so a provider-only app is invisible
 *    in it. That is what happened when the old in-app picker was deleted: upload
 *    flows that route `image/*` to the photo picker (Firefox among them) stopped offering
 *    Immich, while `*/*` flows still worked.
 *
 * This activity is therefore a **bridge**, not a UI. It declares the universal MIME wildcard
 * so every chooser lists the app, and immediately hands off to DocumentsUI opened at the
 * Immich root. The user sees
 * one interface, the same one every other path shows, and the result is forwarded unchanged
 * to whoever started this activity.
 *
 * ## Why not show a grid here
 *
 * An earlier version did exactly that, and it was removed deliberately: two surfaces could
 * not agree on ordering, because DocumentsUI owns the sort of any root it renders. Restoring
 * a full picker would reintroduce that divergence. The bridge shows nothing of its own, so
 * there is nothing to disagree about.
 */
class SafPickerBridgeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImmichService.initialize(this)

        // If anything goes wrong before DocumentsUI takes over, finish rather than leaving
        // an empty screen behind.
        if (savedInstanceState != null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Forward to whichever activity the system uses for OPEN_DOCUMENT (DocumentsUI).
        // No component is named: naming one would bypass the user's picker on devices that
        // substitute their own implementation.
        //
        // The incoming intent is untrusted: this activity is exported, so any app can send
        // it one. Only the three extras needed to preserve the caller's request are copied,
        // and each is re-validated rather than passed through: nothing else from the source
        // intent reaches the outgoing one.
        val forwarded = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = sanitizeType(intent?.type)
            putExtra(Intent.EXTRA_MIME_TYPES, sanitizeTypes(intent))
            if (intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        // Open straight at Immich instead of DocumentsUI's Recent view, so the bridge costs
        // no extra navigation.
        forwarded.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildRootUri(authority(), ROOT_ID),
        )

        Log.d(TAG, "bridging ${intent?.action} (${forwarded.type}) to DocumentsUI")

        // `UnsafeIntentLaunch` is suppressed for this class in app/lint.xml: `forwarded` is a
        // fresh intent whose every field is re-derived and validated, but lint tracks the
        // source intent's taint through `getStringArrayExtra` and cannot see that.
        @Suppress("DEPRECATION")
        startActivityForResult(forwarded, REQUEST_PICK)
    }

    /**
     * Forwards the picker's result verbatim.
     *
     * Deprecated only in the sense that `ActivityResultContracts` is the modern API; a
     * transparent forward needs the raw request code and result, which that API does not
     * express. Nothing here reads or reinterprets the result.
     */
    @Deprecated("startActivityForResult is the simplest way to forward a result unchanged")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return

        setResult(resultCode, data)
        finish()
    }

    private fun authority(): String =
        (packageName ?: BuildConfig.APPLICATION_ID) + ".documents"

    /**
     * Accepts a MIME type only if it is one this activity advertises.
     *
     * The caller is untrusted, so an arbitrary string is not forwarded: it must resemble
     * `family/subtype` and fall inside `image` or `video`, which is all the app can serve.
     */
    private fun sanitizeType(raw: String?): String {
        val type = raw?.trim()?.lowercase()
        if (type.isNullOrEmpty()) return DEFAULT_TYPE
        val allowed = type == ANY_TYPE ||
            type.startsWith("image/") ||
            type.startsWith("video/")
        return if (allowed && MIME_TYPE_PATTERN.matches(type)) type else DEFAULT_TYPE
    }

    /** Same rule for the `EXTRA_MIME_TYPES` list, dropping anything unexpected. */
    private fun sanitizeTypes(source: Intent?): Array<String>? {
        val declared = source?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES) ?: return null
        val clean = declared
            .mapNotNull { raw ->
                val type = raw?.trim()?.lowercase()
                if (type != null && MIME_TYPE_PATTERN.matches(type) &&
                    (type.startsWith("image/") || type.startsWith("video/"))
                ) {
                    type
                } else {
                    null
                }
            }
            .distinct()
        return clean.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        const val TAG = "SafPickerBridge"
        const val REQUEST_PICK = 4101

        /** Root id reported by the provider; see `ImmichDocumentsProvider.ROOT_ID`. */
        const val ROOT_ID = "immich"

        const val DEFAULT_TYPE = "image/*"
        const val ANY_TYPE = "*/*"

        /** Shape check only; the family check happens alongside it. */
        val MIME_TYPE_PATTERN = Regex("^[a-z]+/[a-z0-9.*+-]+$")
    }
}
