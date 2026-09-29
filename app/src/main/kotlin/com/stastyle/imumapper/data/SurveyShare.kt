package com.stastyle.imumapper.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a survey CSV to other apps the way [TripExporter] hands over a trip ZIP. It is kept apart from
 * [SurveyCsvFile] so that everything unit-tested stays free of Android.
 */
object SurveyShare {
    /**
     * ACTION_SEND chooser for a CSV in cache/export, through the `<packageName>.fileprovider` authority
     * ([TripExporter.authority]); the `cache` path in res/xml/file_paths.xml already covers cache/export/.
     */
    fun intent(context: Context, file: File, subject: String, text: String): Intent {
        val uri = FileProvider.getUriForFile(context, TripExporter.authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = SurveyCsvFile.MIME_CSV
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            // Some receivers only honour the grant when the URI is also in the clip data.
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, CHOOSER_TITLE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private const val CHOOSER_TITLE = "Export survey CSV"
}
