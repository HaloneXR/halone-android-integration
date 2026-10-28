package dev.halone.plugin.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/** Apps closed lately, newest first, each with a picture of its window as it was closed. */
class RecentApps(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "recents")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    val changed = LinkedHashSet<() -> Unit>()

    var ids: List<String> = load()
        private set

    /** [picture] is the window's last frame, or null to keep the one from an earlier close. */
    fun add(id: String, picture: HardwareBuffer?) {
        save(listOf(id) + (ids - id))
        if (picture == null) return
        io.execute {
            runCatching { writePreview(id, picture) }.onFailure { Log.w(TAG, "recent preview", it) }
            picture.close()
            main.post(::notifyChanged)
        }
    }

    fun remove(id: String) = save(ids - id)

    fun clear() = save(emptyList())

    /** Decodes [id]'s picture; off the main thread. */
    fun preview(id: String): Bitmap? = previewFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Changes whenever [id]'s picture does, for caching it. */
    fun previewVersion(id: String): Long = previewFile(id).lastModified()

    fun stop() = io.shutdown()

    private fun save(list: List<String>) {
        val kept = list.take(MAX)
        val dropped = ids - kept.toSet()
        ids = kept
        prefs.edit().putString(KEY, JSONArray(kept).toString()).apply()
        if (dropped.isNotEmpty()) io.execute { dropped.forEach { previewFile(it).delete() } }
        notifyChanged()
    }

    private fun load(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun notifyChanged() = changed.toList().forEach { it() }

    private fun previewFile(id: String) = File(dir, id.replace(UNSAFE, "_") + ".jpg")

    private fun writePreview(id: String, picture: HardwareBuffer) {
        val frame = Bitmap.wrapHardwareBuffer(picture, null) ?: return
        val pixels = frame.copy(Bitmap.Config.ARGB_8888, false)
        frame.recycle()
        val preview = Bitmap.createScaledBitmap(upright(pixels), PREVIEW_WIDTH, PREVIEW_HEIGHT, true)
        dir.mkdirs()
        val target = previewFile(id)
        val partial = File(dir, target.name + ".part")
        partial.outputStream().use { preview.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        partial.renameTo(target)
    }

    /** Always portrait: a landscape window keeps its middle, a tall one its top. */
    private fun upright(frame: Bitmap): Bitmap {
        val width = frame.height * PREVIEW_WIDTH / PREVIEW_HEIGHT
        return if (frame.width > width) {
            Bitmap.createBitmap(frame, (frame.width - width) / 2, 0, width, frame.height)
        } else {
            Bitmap.createBitmap(frame, 0, 0, frame.width, minOf(frame.height, frame.width * PREVIEW_HEIGHT / PREVIEW_WIDTH))
        }
    }

    private companion object {
        const val TAG = "HaloneAndroidApps"
        const val KEY = "ids"
        const val MAX = 12
        const val PREVIEW_WIDTH = 240
        const val PREVIEW_HEIGHT = 372
        const val JPEG_QUALITY = 85
        val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}
