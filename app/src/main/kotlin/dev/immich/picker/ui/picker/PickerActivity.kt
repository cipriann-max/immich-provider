package dev.immich.picker.ui.picker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.PickerAsset
import dev.immich.picker.ui.MainActivity
import dev.immich.picker.util.MimeFilters

/**
 * The in-app picker.
 *
 * ## Why this exists
 *
 * A DocumentsProvider does not control how its content is presented: DocumentsUI owns
 * the layout, the grid density and — critically — the sort order. It ignores the order
 * the provider returns and defaults to "File name (A to Z)", which makes a
 * reverse-chronological photo library look wrong and cannot be changed from provider
 * code. Owning an Activity is the only way to order by date and page through the whole
 * library the way a photo picker should.
 *
 * ## Launch modes
 *
 * [Mode.PICK] — the activity is the content picker. Used when an app asks for
 * [Intent.ACTION_GET_CONTENT] or [Intent.ACTION_OPEN_DOCUMENT] and the user chooses
 * Immich. Selected assets are returned as `content://` URIs whose authority is our
 * DocumentsProvider, so the receiver streams the original through
 * [dev.immich.picker.provider.ImmichDocumentsProvider.openDocument] exactly as it would
 * for any other SAF source.
 *
 * [Mode.BROWSE] — the activity was opened from DocumentsUI's per-root "open in app"
 * arrow, which sends [android.provider.DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS].
 * That intent is a *settings/browse* intent: DocumentsUI does not consume a result from
 * it and does not forward one to the app that originally opened the picker. Selecting is
 * therefore disabled here and the grid is presented read-only, which is honest about
 * what this path can do. Attaching a file has to happen inside the picker itself; the
 * provider root remains available there for exactly that.
 */
class PickerActivity : ComponentActivity() {

    private val viewModel: PickerViewModel by viewModels()

    private var mode: Mode = Mode.BROWSE
    private var allowMultiple: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT enableEdgeToEdge(): the picker is a full-screen browsing
        // surface, and drawing under the status bar only produced a top row of content
        // hidden behind the clock and notification icons. Letting the system own the
        // status bar keeps it visible and correct in both gesture and 3-button modes.
        ImmichService.initialize(this)

        val action = intent?.action
        mode = when (action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT -> Mode.PICK
            else -> Mode.BROWSE
        }
        allowMultiple = intent?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true

        viewModel.configure(
            maxSelection = when {
                mode == Mode.BROWSE -> 0
                allowMultiple -> Int.MAX_VALUE
                else -> 1
            },
        )
        // Only a real picker has a caller to satisfy, so an unsatisfiable request is
        // only reported when there is one.
        viewModel.setMimeFilter(if (mode == Mode.PICK) requestedMimeTypes() else emptyList())
        viewModel.start()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.back()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        setContent {
            MaterialTheme {
                Surface {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    PickerScreen(
                        state = state,
                        readOnly = mode == Mode.BROWSE,
                        onBack = { if (!viewModel.back()) finish() },
                        onSortToggle = viewModel::toggleSort,
                        onOpenAlbums = { viewModel.open(PickerLocation.Albums) },
                        onOpenFavorites = { viewModel.open(PickerLocation.Favorites) },
                        onOpenRecents = { viewModel.open(PickerLocation.Recents) },
                        onOpenAlbum = { viewModel.open(PickerLocation.Album(it)) },
                        onToggleAsset = viewModel::toggleAssetFiltered,
                        onClearSelection = viewModel::clearSelection,
                        onConfirm = ::deliverResult,
                        onLoadMore = viewModel::loadMore,
                        onRetry = viewModel::refreshCurrent,
                        onLogin = {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the login screen should repopulate the grid without a restart.
        if (ImmichService.isLoggedIn() && viewModel.state.value.needsLogin) {
            viewModel.start()
        }
    }

    // ------------------------------------------------------------------ result

    /**
     * Hands the chosen assets to the calling picker.
     *
     * Both `data` and `clipData` are populated: single-selection callers read
     * `getData()`, while `EXTRA_ALLOW_MULTIPLE` callers read `getClipData()`. Setting
     * both avoids the "no data returned" failure some receiving apps hit when only one
     * of the two is supplied.
     */
    private fun deliverResult() {
        if (mode != Mode.PICK) return

        val assets: List<PickerAsset> = viewModel.selectedAssets()
        if (assets.isEmpty()) return

        val uris: List<Uri> = assets.map(::contentUriFor)

        val result = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (intent?.action == Intent.ACTION_OPEN_DOCUMENT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            setDataAndType(uris.first(), assets.first().mimeType)
            clipData = ClipData.newRawUri("Immich", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun contentUriFor(asset: PickerAsset): Uri =
        android.provider.DocumentsContract.buildDocumentUri(
            "$packageName.documents",
            "asset:${asset.id}",
        )

    /**
     * The MIME types the caller accepts.
     *
     * `EXTRA_MIME_TYPES` is read first because a document picker commonly sends the
     * universal wildcard as the intent type and the real list in that extra; ignoring it
     * would offer files the caller cannot use.
     */
    private fun requestedMimeTypes(): List<String> = MimeFilters.parse(
        type = intent?.type,
        extraTypes = intent?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
    )

    private enum class Mode {
        /** Launched as a real content picker; returns a result. */
        PICK,

        /** Launched for browsing/settings; selection is disabled. */
        BROWSE,
    }
}
