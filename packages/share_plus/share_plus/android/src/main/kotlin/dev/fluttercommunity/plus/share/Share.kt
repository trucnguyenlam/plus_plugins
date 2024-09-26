package dev.fluttercommunity.plus.share

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Handles share intent. The `context` and `activity` are used to start the share
 * intent. The `activity` might be null when constructing the [Share] object and set
 * to non-null when an activity is available using [.setActivity].
 */
internal class Share(
    private val context: Context,
    private var activity: Activity?,
    private val manager: ShareSuccessManager
) {
    private val providerAuthority: String by lazy {
        getContext().packageName + ".flutter.share_provider"
    }

    /**
     * Setting mutability flags as API v31+ requires.
     */
    private val immutabilityIntentFlags: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }

    private fun getContext(): Context {
        return if (activity != null) {
            activity!!
        } else {
            context
        }
    }

    /**
     * Sets the activity when an activity is available. When the activity becomes unavailable, use
     * this method to set it to null.
     */
    fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    fun share(text: String, subject: String?, withResult: Boolean) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
        }
        // If we dont want the result we use the old 'createChooser'
        val chooserIntent =
            if (withResult && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Build chooserIntent with broadcast to ShareSuccessManager on success
                Intent.createChooser(
                    shareIntent,
                    null, // dialog title optional
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, SharePlusPendingIntent::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or immutabilityIntentFlags
                    ).intentSender
                )
            } else {
                Intent.createChooser(shareIntent, null /* dialog title optional */)
            }
        startActivity(chooserIntent, withResult)
    }

    @Throws(IOException::class)
    fun shareFiles(
        paths: List<String>,
        contentUris: List<String>?,
        mimeTypes: List<String>?,
        panelTitle: String?,
        text: String?,
        subject: String?,
        targetPackage: String?,
        isContentUri: String?,
        withResult: Boolean
    ) {
        val usbMassStorage = "true" == isContentUri
        val fileUris = getUrisForPaths(paths, contentUris, usbMassStorage)
        val shareIntent = Intent()
        when {
            (fileUris.isEmpty() && !text.isNullOrBlank()) -> {
                share(text, subject, withResult)
                return
            }

            fileUris.size == 1 -> {
                val mimeType = if (!mimeTypes.isNullOrEmpty()) {
                    mimeTypes.first()
                } else {
                    "*/*"
                }
                shareIntent.apply {
                    action = Intent.ACTION_SEND
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, fileUris.first())
                }
            }

            else -> {
                shareIntent.apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = reduceMimeTypes(mimeTypes)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, fileUris)
                }
            }
        }
        if (text != null) shareIntent.putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (targetPackage != null) shareIntent.`package` = targetPackage
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // If we dont want the result we use the old 'createChooser'
        val chooserIntent =
            if (withResult && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Build chooserIntent with broadcast to ShareSuccessManager on success
                Intent.createChooser(
                    shareIntent,
                    panelTitle, // dialog title optional
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, SharePlusPendingIntent::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or immutabilityIntentFlags
                    ).intentSender
                )
            } else {
                Intent.createChooser(shareIntent, panelTitle /* dialog title optional */)
            }
        val resInfoList = getContext().packageManager.queryIntentActivities(
            chooserIntent, PackageManager.MATCH_DEFAULT_ONLY
        )
        resInfoList.forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            fileUris.forEach { fileUri ->
                getContext().grantUriPermission(
                    packageName,
                    fileUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        startActivity(chooserIntent, withResult)
    }

    private fun startActivity(intent: Intent, withResult: Boolean) {
        if (activity != null) {
            if (withResult) {
                activity!!.startActivityForResult(intent, ShareSuccessManager.ACTIVITY_CODE)
            } else {
                activity!!.startActivity(intent)
            }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (withResult) {
                // We need to cancel the callback to avoid deadlocking on the Dart side
                manager.unavailable()
            }
            context.startActivity(intent)
        }
    }

    @Throws(IOException::class)
    private fun getUrisForPaths(paths: List<String>, contentUris: List<String>?, usbMassStorage: Boolean): ArrayList<Uri> {
        val uris = ArrayList<Uri>(paths.size)
        // Android sdk 30+, need content uri instead
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || usbMassStorage) && !contentUris.isNullOrEmpty()) {
            contentUris.forEach { path ->
                uris.add(Uri.parse(path))
            }
        } else {
            paths.forEach { path ->
                uris.add(FileProvider.getUriForFile(getContext(), providerAuthority, File(path)))
            }
        }
        return uris
    }

    /**
     * Reduces provided MIME types to a common one to provide [Intent] with a correct type to share
     * multiple files
     */
    private fun reduceMimeTypes(mimeTypes: List<String>?): String {
        if (mimeTypes?.isEmpty() != false) return "*/*"
        if (mimeTypes.size == 1) return mimeTypes.first()

        var commonMimeType = mimeTypes.first()
        for (i in 1..mimeTypes.lastIndex) {
            if (commonMimeType != mimeTypes[i]) {
                if (getMimeTypeBase(commonMimeType) == getMimeTypeBase(mimeTypes[i])) {
                    commonMimeType = getMimeTypeBase(mimeTypes[i]) + "/*"
                } else {
                    commonMimeType = "*/*"
                    break
                }
            }
        }
        return commonMimeType
    }

    /**
     * Returns the first part of provided MIME type, which comes before '/' symbol
     */
    private fun getMimeTypeBase(mimeType: String?): String {
        return if (mimeType == null || !mimeType.contains("/")) {
            "*"
        } else {
            mimeType.substring(0, mimeType.indexOf("/"))
        }
    }
}
