package dev.halone.plugin.android

import android.content.Intent
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.halone.plugin.PanelSession
import java.util.concurrent.Executors

/** A phone app's task, shown on a trusted virtual display created on this window's surface. */
class AppPanelSession(
    private val provider: AndroidAppsProvider,
    private val label: String,
    private val launch: Intent,
    /** The catalog app shown, kept in recents when closed; null for a settings screen. */
    private val appId: String? = null,
) : PanelSession() {
    private val privileged get() = provider.privileged
    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var displayId = NO_DISPLAY
    private var displayService: IDisplayService? = null
    private var card: NoticeCard? = null
    private val onPrivilegedChanged: () -> Unit = { connect() }
    private val displayCallbacks = object : Privileged.DisplayCallbacks {
        override fun onEmptied() = host.close()

        override fun onOrientationRequested(orientation: Int) {
            val landscape = widthPx > heightPx
            val wanted = when (orientation) {
                ORIENTATION_LANDSCAPE -> true
                ORIENTATION_PORTRAIT -> false
                else -> return
            }
            if (wanted != landscape) host.requestResize(heightPx, widthPx)
        }
    }
    private val applyResize = Runnable { call { it.resizeDisplay(displayId, widthPx, heightPx, displayDpi()) } }

    /**
     * By the time Halone closes a window it has let go of its surface, and a
     * display with nothing to draw into has nothing to snapshot. So while the
     * app is on show its picture is kept fresh, for recents to fall back on.
     */
    private val snapshots = Executors.newSingleThreadExecutor()
    private var lastPicture: HardwareBuffer? = null
    private val keepPicture = object : Runnable {
        override fun run() {
            main.postDelayed(this, PICTURE_INTERVAL_MS)
            if (surface == null || !provider.recentsOn || appId == null) return
            val id = displayId
            val service = displayService ?: return
            if (id == NO_DISPLAY) return
            snapshots.execute {
                val picture = runCatching { service.captureDisplay(id) }.getOrNull() ?: return@execute
                main.post {
                    if (snapshots.isShutdown) return@post picture.close()
                    lastPicture?.close()
                    lastPicture = picture
                }
            }
        }
    }

    override fun onAttach(surface: Surface) {
        host.setTitle(label)
        host.setBackButton(true)
        host.setSizeLimits(MIN_SIZE_PX, MIN_SIZE_PX, MAX_SIZE_PX, MAX_SIZE_PX)
        this.surface = surface
        privileged.addListener(onPrivilegedChanged)
        connect()
        main.removeCallbacks(keepPicture)
        main.postDelayed(keepPicture, PICTURE_INTERVAL_MS)
    }

    override fun onResize() {
        card?.resize(widthPx, heightPx, densityDpi)
        main.removeCallbacks(applyResize)
        if (displayId != NO_DISPLAY) main.postDelayed(applyResize, RESIZE_SETTLE_MS)
    }

    override fun onDetach() {
        main.removeCallbacks(keepPicture)
        surface = null
        card?.release()
        card = null
        call { it.setSurface(displayId, null) }
    }

    override fun onClose() {
        privileged.removeListener(onPrivilegedChanged)
        main.removeCallbacks(applyResize)
        main.removeCallbacks(keepPicture)
        card?.release()
        card = null
        keepInRecents()
        releaseDisplay()
        surface = null
    }

    private fun keepInRecents() {
        snapshots.shutdown()
        val earlier = lastPicture
        lastPicture = null
        val id = appId
        if (id == null || !provider.recentsOn || displayId == NO_DISPLAY) return earlier?.close() ?: Unit
        var picture: HardwareBuffer? = null
        call { picture = it.captureDisplay(displayId) }
        if (picture != null) earlier?.close()
        provider.recents.add(id, picture ?: earlier)
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (displayId != NO_DISPLAY) {
            call { it.injectMotion(displayId, event) }
        } else if (event.actionMasked == MotionEvent.ACTION_UP) {
            privileged.requestAccess()
        }
    }

    override fun onKeyEvent(event: KeyEvent) {
        call { it.injectKey(displayId, event) }
    }

    override fun onBack() {
        call { it.pressBack(displayId) }
    }

    override fun onContextMenu(x: Float, y: Float) {
        call { it.contextClick(displayId, x, y) }
    }

    private fun connect() {
        val target = surface ?: return
        val service = privileged.service
        if (privileged.state != Privileged.State.READY || service == null) {
            if (displayId != NO_DISPLAY) privileged.watchDisplay(displayId, null)
            displayService = null
            displayId = NO_DISPLAY
            return showNotice(privileged.state)
        }
        if (displayService === service && displayId != NO_DISPLAY) {
            call { it.setSurface(displayId, target) }
            return
        }
        card?.release()
        card = null
        try {
            displayId = service.createDisplay("halone-${launch.component?.packageName ?: launch.action}", target, widthPx, heightPx, displayDpi())
            displayService = service
            privileged.watchDisplay(displayId, displayCallbacks)
            val error = service.startActivity(displayId, Intent(launch))
            if (error != null) fail(error)
        } catch (e: RemoteException) {
            fail(e.toString())
        } catch (e: RuntimeException) {
            fail(e.toString())
        }
    }

    private fun fail(error: String) {
        Log.w(TAG, "$label: $error")
        releaseDisplay()
        notice(context.getString(R.string.notice_failed_title, label), error)
    }

    /** Past [MAX_LAYOUT_PX] a bigger window magnifies the app instead of giving it more room, so layouts stay phone- and tablet-sized. */
    private fun displayDpi(): Int {
        val scale = maxOf(1f, maxOf(widthPx, heightPx).toFloat() / MAX_LAYOUT_PX)
        return (densityDpi * scale).toInt()
    }

    private fun releaseDisplay() {
        if (displayId == NO_DISPLAY) return
        privileged.watchDisplay(displayId, null)
        call { it.releaseDisplay(displayId) }
        displayId = NO_DISPLAY
        displayService = null
    }

    private fun showNotice(state: Privileged.State) {
        val (title, body) = when (state) {
            Privileged.State.NO_SHIZUKU -> R.string.notice_no_shizuku_title to R.string.notice_no_shizuku_body
            Privileged.State.NOT_RUNNING -> R.string.notice_not_running_title to R.string.notice_not_running_body
            Privileged.State.NO_PERMISSION -> R.string.notice_permission_title to R.string.notice_permission_body
            Privileged.State.DENIED -> R.string.notice_denied_title to R.string.notice_denied_body
            Privileged.State.CONNECTING, Privileged.State.READY -> R.string.notice_starting_title to R.string.notice_starting_body
        }
        notice(context.getString(title), context.getString(body))
    }

    private fun notice(title: String, body: String) {
        val target = surface ?: return
        val shown = card ?: NoticeCard(context, target, widthPx, heightPx, densityDpi).also { card = it }
        shown.show(title, body)
    }

    private inline fun call(block: (IDisplayService) -> Unit) {
        if (displayId == NO_DISPLAY) return
        val service = displayService ?: return
        try {
            block(service)
        } catch (e: RemoteException) {
            Log.w(TAG, "display service", e)
        }
    }

    private companion object {
        const val TAG = "HaloneAndroidApps"
        const val NO_DISPLAY = -1
        const val RESIZE_SETTLE_MS = 400L
        const val PICTURE_INTERVAL_MS = 3_000L
        const val MIN_SIZE_PX = 320
        const val MAX_SIZE_PX = 2560
        const val MAX_LAYOUT_PX = 1280
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
