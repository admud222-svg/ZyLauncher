package org.levimc.launcher.zy

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import kotlin.math.roundToInt

/**
 * Zy Boost: performance options applied to the Minecraft activity.
 *
 *  - render resolution scale (SurfaceHolder.setFixedSize) with matching touch-coordinate scaling
 *  - highest refresh rate display mode + Surface frame-rate hint
 *  - sustained performance mode (stable clocks instead of boost-then-throttle)
 *  - game-thread priority: the busiest game threads get a higher priority and the fast CPU cores
 *  - background cleanup (cached apps) before the game starts
 *  - light graphics presets written into options.txt (with a one-time backup)
 */
object ZyBoost {
    private const val TAG = "ZyBoost"
    private const val PREFS = "zy_boost"

    const val KEY_SCALE = "render_scale"
    const val KEY_MAX_REFRESH = "max_refresh"
    const val KEY_SUSTAINED = "sustained"
    const val KEY_THREADS = "thread_boost"
    const val KEY_CLEAN = "clean_ram"
    const val KEY_KEEP_ALIVE = "keep_alive"
    const val KEY_FAST_START = "fast_start"

    const val SCALE_MIN = 50
    const val SCALE_MAX = 100

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scalePercent(context: Context): Int =
        prefs(context).getInt(KEY_SCALE, SCALE_MAX).coerceIn(SCALE_MIN, SCALE_MAX)

    private fun flag(context: Context, key: String, default: Boolean = false) =
        prefs(context).getBoolean(key, default)

    /** Options that are on unless the player turns them off. */
    fun defaultOn(key: String): Boolean = key == KEY_KEEP_ALIVE || key == KEY_FAST_START

    fun isOn(context: Context, key: String): Boolean = flag(context, key, defaultOn(key))

    /** Skip the decorative launcher splash animation (it does no work, it only waits ~3 s). */
    fun fastStart(context: Context): Boolean = isOn(context, KEY_FAST_START)

    // ---- runtime state for the running game ---------------------------------------------------

    @Volatile private var touchScaleX = 1f
    @Volatile private var touchScaleY = 1f
    @Volatile private var threadBoostRunning = false

    /** Called once from MinecraftActivity after Mojang's onCreate. Never throws. */
    fun onGameCreated(activity: Activity, surfaceView: SurfaceView?) {
        val ctx = activity.applicationContext
        safe("refresh") { if (flag(ctx, KEY_MAX_REFRESH)) applyMaxRefresh(activity) }
        safe("sustained") { if (flag(ctx, KEY_SUSTAINED)) applySustained(activity) }
        safe("scale") { if (surfaceView != null) attachScale(surfaceView, scalePercent(ctx)) }
        safe("threads") { if (flag(ctx, KEY_THREADS)) startThreadBoost() }
        safe("clean") { if (flag(ctx, KEY_CLEAN)) Thread({ cleanBackground(ctx) }, "ZyClean").start() }
        safe("keepAlive") { if (isOn(ctx, KEY_KEEP_ALIVE)) ZyKeepAliveService.start(ctx) }
    }

    // ---- resolution scale -----------------------------------------------------------------------

    private fun attachScale(view: SurfaceView, percent: Int) {
        if (percent >= SCALE_MAX) return
        val factor = percent / 100f
        val apply = {
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                val fw = (w * factor).roundToInt().coerceAtLeast(1)
                val fh = (h * factor).roundToInt().coerceAtLeast(1)
                touchScaleX = fw.toFloat() / w
                touchScaleY = fh.toFloat() / h
                view.holder.setFixedSize(fw, fh)
                Log.i(TAG, "render ${fw}x$fh of ${w}x$h ($percent%)")
            }
        }
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) apply()
        }
        view.post { apply() }
    }

    /** True when touch coordinates must be converted to the smaller render surface. */
    fun isScaled(): Boolean = touchScaleX != 1f || touchScaleY != 1f

    fun scaleX(x: Float): Float = x * touchScaleX
    fun scaleY(y: Float): Float = y * touchScaleY

    /** A scaled copy of [event] (caller recycles it), or null when no scaling is needed. */
    fun scaledCopy(event: MotionEvent): MotionEvent? {
        if (!isScaled()) return null
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return null // relative deltas
        val copy = MotionEvent.obtain(event)
        copy.transform(Matrix().apply { setScale(touchScaleX, touchScaleY) })
        return copy
    }

    // ---- display ---------------------------------------------------------------------------------

    private fun applyMaxRefresh(activity: Activity) {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay)
            ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = best.modeId
        attrs.preferredRefreshRate = best.refreshRate
        activity.window.attributes = attrs
        Log.i(TAG, "display mode ${best.modeId} @ ${best.refreshRate}Hz")
    }

    /** Frame-rate hint for the game surface (Android 11+). Registered as an extra holder callback. */
    fun refreshCallback(context: Context): SurfaceHolder.Callback? {
        if (Build.VERSION.SDK_INT < 30 || !flag(context, KEY_MAX_REFRESH)) return null
        return object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = hint(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = hint(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
            private fun hint(holder: SurfaceHolder) {
                if (Build.VERSION.SDK_INT < 30) return
                try {
                    holder.surface.setFrameRate(240f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                } catch (t: Throwable) {
                    Log.w(TAG, "frame rate hint failed: $t")
                }
            }
        }
    }

    private fun applySustained(activity: Activity) {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isSustainedPerformanceModeSupported) {
            activity.window.setSustainedPerformanceMode(true)
            Log.i(TAG, "sustained performance mode on")
        }
    }

    // ---- thread priority / fast cores -----------------------------------------------------------

    /** CPU ids of the fastest cluster (plus the next one when the fastest has fewer than 3 cores). */
    fun fastCores(): List<Int> {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            val f = readLong("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq") ?: return@mapNotNull null
            cpu to f
        }
        val distinct = freqs.map { it.second }.distinct().sortedDescending()
        if (distinct.size < 2) return emptyList()
        var chosen = freqs.filter { it.second == distinct[0] }.map { it.first }
        if (chosen.size < 3) chosen = freqs.filter { it.second >= distinct[1] }.map { it.first }
        return chosen.sorted()
    }

    private fun startThreadBoost() {
        if (threadBoostRunning) return
        threadBoostRunning = true
        Thread({
            val cores = fastCores()
            val mask = cores.fold(0L) { m, c -> m or (1L shl c) }
            val boosted = HashSet<Int>()
            var rounds = 0
            while (threadBoostRunning) {
                try {
                    for (tid in busiestThreads(3)) {
                        if (boosted.add(tid)) {
                            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
                            if (mask != 0L) setAffinity(tid, mask)
                            Log.i(TAG, "boosted thread $tid (${threadName(tid)})")
                        }
                    }
                } catch (_: Throwable) {}
                rounds++
                try { Thread.sleep(if (rounds < 12) 5_000L else 30_000L) } catch (_: InterruptedException) { break }
            }
        }, "ZyThreadBoost").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** The [n] threads of this process that used the most CPU over a 2-second window. */
    private fun busiestThreads(n: Int): List<Int> {
        val self = Process.myTid()
        val first = threadTimes()
        Thread.sleep(2_000L)
        val second = threadTimes()
        return second.mapNotNull { (tid, t) -> first[tid]?.let { tid to (t - it) } }
            .filter { it.first != self && it.second >= 40 } // >= 20 % of one core (100 ticks/s)
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
    }

    private fun threadTimes(): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        File("/proc/self/task").listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            val stat = try { File(dir, "stat").readText() } catch (_: Throwable) { return@forEach }
            val fields = stat.substringAfterLast(')').trim().split(' ')
            // fields[0] is field 3 (state); utime = field 14, stime = field 15
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return@forEach
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return@forEach
            out[tid] = utime + stime
        }
        return out
    }

    private fun threadName(tid: Int): String =
        try { File("/proc/self/task/$tid/comm").readText().trim() } catch (_: Throwable) { "?" }

    /** sched_setaffinity through toybox `taskset` (no native code needed; same-uid threads only). */
    private fun setAffinity(tid: Int, mask: Long) {
        try {
            val p = ProcessBuilder("taskset", "-p", java.lang.Long.toHexString(mask), tid.toString())
                .redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor()
        } catch (_: Throwable) {}
    }

    // ---- background cleanup ---------------------------------------------------------------------

    /** Kills cached background processes of other user apps. Returns how many apps were asked. */
    fun cleanBackground(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager
        var count = 0
        @Suppress("DEPRECATION")
        val apps = try { pm.getInstalledApplications(0) } catch (_: Throwable) { emptyList<ApplicationInfo>() }
        for (app in apps) {
            if (app.packageName == context.packageName) continue
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            if (app.packageName == "com.mojang.minecraftpe" || app.packageName.startsWith("moe.shizuku")) continue
            try { am.killBackgroundProcesses(app.packageName); count++ } catch (_: Throwable) {}
        }
        Log.i(TAG, "background cleanup: $count apps")
        return count
    }

    fun memoryText(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "RAM bebas ${mi.availMem / 1_048_576} MB dari ${mi.totalMem / 1_048_576} MB"
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun readLong(path: String): Long? = try { File(path).readText().trim().toLong() } catch (_: Throwable) { null }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what failed: $t") }
    }
}
