package com.stastyle.imumapper.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer. Android refuses unless the user has
 * allowed this app to install unknown apps, so the first attempt usually opens that settings page.
 */
class UpdateInstaller(context: Context) {

    private val context: Context = context.applicationContext

    sealed interface Result {
        /** The installer UI was started; Android takes over from here. */
        data object Launched : Result

        /** The "install unknown apps" settings page was opened; the user must allow and tap again. */
        data object NeedsPermission : Result

        data class Failed(val message: String) : Result
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(apk: File): Result {
        if (!apk.isFile) return Result.Failed("The downloaded update is missing; download it again")
        if (!canInstall()) return openUnknownSourcesSettings()
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (e: IllegalArgumentException) {
            return Result.Failed("The update file is outside the shareable folder: ${apk.path}")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Launched
        } catch (e: ActivityNotFoundException) {
            Result.Failed("No app on this device can install packages")
        }
    }

    private fun openUnknownSourcesSettings(): Result {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.NeedsPermission
        } catch (e: ActivityNotFoundException) {
            Result.Failed("Allow \"Install unknown apps\" for IMU Mapper in the system settings, then try again")
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
