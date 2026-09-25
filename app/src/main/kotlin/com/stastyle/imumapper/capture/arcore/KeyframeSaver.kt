package com.stastyle.imumapper.capture.arcore

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import android.media.Image
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns an ARCore CPU image into `kf-NNNN.jpg` in the trip's photo directory on its own thread. Only one
 * save runs at a time; while it runs [trySave] refuses new images so the GL thread never queues work.
 */
class KeyframeSaver(
    private val photosDir: File,
    /** EXIF orientation tag (1, 3, 6 or 8) so viewers show the landscape sensor image upright. */
    private val exifOrientation: Int = 1,
    private val quality: Int = KEYFRAME_JPEG_QUALITY,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ar-keyframe") }
    private val busy = AtomicBoolean(false)

    val isIdle: Boolean get() = !busy.get()

    /**
     * Takes ownership of [image] in every case (it is closed here whether or not the save happens).
     * Returns false when a previous save is still running; [onDone] is then never called.
     */
    fun trySave(image: Image, fileName: String, onDone: (Boolean) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return false
        }
        try {
            executor.execute { save(image, fileName, onDone) }
        } catch (e: RejectedExecutionException) {
            image.close()
            busy.set(false)
            return false
        }
        return true
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun save(image: Image, fileName: String, onDone: (Boolean) -> Unit) {
        var ok = false
        try {
            val width = image.width
            val height = image.height
            // Convert first and release the image immediately: ARCore only lends out a few at a time.
            val nv21 = try {
                val planes = image.planes
                yuv420ToNv21(
                    width, height,
                    PlaneData(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                    PlaneData(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                    PlaneData(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
                )
            } finally {
                image.close()
            }
            val target = File(photosDir, fileName)
            val tmp = File(photosDir, "$fileName.tmp")
            FileOutputStream(tmp).use { out ->
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)) {
                    throw IllegalStateException("JPEG compression failed")
                }
            }
            writeOrientation(tmp)
            ok = tmp.renameTo(target)
            if (!ok) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "keyframe $fileName not saved", e)
            File(photosDir, "$fileName.tmp").delete()
        } finally {
            busy.set(false)
            onDone(ok)
        }
    }

    private fun writeOrientation(file: File) {
        if (exifOrientation == 1) return
        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
        } catch (e: Exception) {
            // The photo is still valid, just shown rotated; not worth failing the keyframe.
            Log.w(TAG, "EXIF orientation not written", e)
        }
    }

    private companion object {
        const val TAG = "KeyframeSaver"
    }
}
