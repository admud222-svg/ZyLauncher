package org.levimc.launcher.zy

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Zy Boost settings screen (built in code; no layout XML). */
class ZyBoostActivity : AppCompatActivity() {

    private val bg = Color.parseColor("#0F1317")
    private val card = Color.parseColor("#1A2027")
    private val accent = Color.parseColor("#F5B428")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#9AA4AE")

    private lateinit var list: LinearLayout
    private lateinit var memory: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = ZyBoost.prefs(this)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(list, MATCH_PARENT, WRAP_CONTENT)
        })

        list.addView(text("Zy Boost", 26f, textMain, bold = true))
        list.addView(text("Berlaku saat game dibuka berikutnya.", 14f, textDim).also { it.setPadding(0, dp(2), 0, dp(14)) })

        // Resolution
        val scaleLabel = text("", 15f, accent, bold = true)
        fun showScale(p: Int) {
            scaleLabel.text = if (p >= ZyBoost.SCALE_MAX) "100% (mati)" else "$p%"
        }
        val seek = SeekBar(this).apply {
            max = (ZyBoost.SCALE_MAX - ZyBoost.SCALE_MIN) / 5
            progress = (ZyBoost.scalePercent(this@ZyBoostActivity) - ZyBoost.SCALE_MIN) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    val p = ZyBoost.SCALE_MIN + value * 5
                    showScale(p)
                    prefs.edit().putInt(ZyBoost.KEY_SCALE, p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        showScale(ZyBoost.scalePercent(this))
        list.addView(section("Resolusi Render",
            "Makin kecil makin ringan (FPS naik), gambar sedikit lebih halus/buram. 70-85% biasanya paling pas.",
            column(scaleLabel, seek)))

        // Refresh rate
        val maxHz = maxRefreshRate()
        list.addView(section("Refresh Rate Maksimal",
            if (maxHz > 61f) "Pakai mode layar tercepat (${maxHz.toInt()}Hz)." else "Layar HP ini cuma 60Hz.",
            switch(ZyBoost.KEY_MAX_REFRESH, enabled = maxHz > 61f)))

        // Sustained performance
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val sustainedOk = pm.isSustainedPerformanceModeSupported
        list.addView(section("Performa Stabil",
            if (sustainedOk) "CPU/GPU dijaga stabil biar FPS gak anjlok pas HP mulai panas."
            else "HP ini tidak mendukung mode performa stabil.",
            switch(ZyBoost.KEY_SUSTAINED, enabled = sustainedOk)))

        // Thread boost
        val fast = ZyBoost.fastCores()
        list.addView(section("Prioritas Game",
            "Thread game tersibuk dapat prioritas tinggi" +
                (if (fast.isNotEmpty()) " dan dipindah ke core cepat (CPU ${fast.joinToString(",")})." else "."),
            switch(ZyBoost.KEY_THREADS)))

        // RAM
        memory = text(ZyBoost.memoryText(this), 13f, textDim)
        val cleanNow = button("Bersihkan Sekarang") {
            Thread {
                val n = ZyBoost.cleanBackground(this)
                runOnUiThread {
                    memory.text = ZyBoost.memoryText(this)
                    toast("Menutup $n app background")
                }
            }.start()
        }
        list.addView(section("Bersihkan RAM",
            "Tutup app di background otomatis sebelum game jalan.",
            column(switch(ZyBoost.KEY_CLEAN), memory, cleanNow)))

        // Presets
        list.addView(section("Preset Grafik",
            "Ubah setting grafik game jadi ringan. Setting asli kamu disimpan dan bisa dikembalikan.",
            column(
                button("Ringan (6 chunk)") { applyPreset(ZyPresets.LIGHT) },
                button("Super Ringan (4 chunk)") { applyPreset(ZyPresets.ULTRA) },
                button("Kembalikan Setting Asli") {
                    val n = ZyPresets.restore(this)
                    toast(if (n > 0) "Setting asli dikembalikan" else "Belum ada setting yang diubah")
                })))

        list.addView(text(
            "Zy Launcher juga terdaftar sebagai game di Android, jadi Game Booster / Game Dashboard HP kamu bisa ikut dipakai.\n\n" +
                "Berbasis LeviLauncher oleh LeviMC (Apache 2.0).",
            12f, textDim).also { it.setPadding(dp(4), dp(16), dp(4), 0) })
    }

    private fun applyPreset(preset: Map<String, String>) {
        val files = ZyPresets.optionFiles(this)
        if (files.isEmpty()) { toast("Buka game sekali dulu, terus pasang preset lagi"); return }
        val n = ZyPresets.apply(this, preset)
        toast(if (n > 0) "Preset dipasang" else "Setting sudah sesuai preset")
    }

    private fun maxRefreshRate(): Float = try {
        @Suppress("DEPRECATION")
        val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 60f
    } catch (_: Throwable) { 60f }

    // ---- small view helpers -----------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
    }

    private fun section(title: String, desc: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat() }
        addView(text(title, 17f, textMain, bold = true))
        addView(text(desc, 13f, textDim).also { it.setPadding(0, dp(3), 0, dp(4)) })
        addView(control)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun switch(key: String, enabled: Boolean = true) = SwitchCompat(this).apply {
        text = if (enabled) "Aktif" else "Tidak tersedia"
        setTextColor(textMain)
        gravity = Gravity.CENTER_VERTICAL
        isEnabled = enabled
        isChecked = enabled && ZyBoost.prefs(this@ZyBoostActivity).getBoolean(key, false)
        setOnCheckedChangeListener { _, on -> ZyBoost.prefs(this@ZyBoostActivity).edit().putBoolean(key, on).apply() }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.parseColor("#1A1300"))
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(10).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
