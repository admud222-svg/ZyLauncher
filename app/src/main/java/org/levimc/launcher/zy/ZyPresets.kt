package org.levimc.launcher.zy

import android.content.Context
import org.levimc.launcher.util.LauncherStorage
import java.io.File

/**
 * Light graphics presets for Minecraft's options.txt. Only keys that already exist in the file are
 * changed, and the first time a file is touched a copy is kept as options.txt.zybak so "Kembalikan"
 * can restore the player's own settings.
 */
object ZyPresets {
    private const val BACKUP_SUFFIX = ".zybak"

    private val COMMON = linkedMapOf(
        "graphics_mode" to "0",               // Simple
        "gfx_fancyskies" to "0",
        "gfx_transparentleaves" to "0",
        "gfx_smoothlighting" to "0",
        "gfx_toggleclouds" to "0",
        "cloud_quality" to "0",
        "bloom_strength" to "0",
        "gfx_particleviewdistance" to "0",
        "gfx_bubble_particles" to "0",
        "gfx_viewbobbing" to "0",
        "gfx_msaa" to "1",
        "gfx_texel_aa_2" to "0",
        "gfx_multithreaded_renderer" to "1",
    )

    /** "Ringan": 6 chunks. */
    val LIGHT: Map<String, String> = COMMON + mapOf("gfx_viewdistance" to "96")

    /** "Super Ringan": 4 chunks. */
    val ULTRA: Map<String, String> = COMMON + mapOf("gfx_viewdistance" to "64")

    fun optionFiles(context: Context): List<File> {
        val root = try { LauncherStorage.getMinecraftRoot(context) } catch (_: Throwable) { null } ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(8)
            .filter { it.isFile && it.name == "options.txt" && it.parentFile?.name == "minecraftpe" }
            .toList()
    }

    /** Applies [preset] to every options.txt; returns how many files changed. */
    fun apply(context: Context, preset: Map<String, String>): Int {
        var changed = 0
        for (file in optionFiles(context)) {
            try {
                val backup = File(file.path + BACKUP_SUFFIX)
                if (!backup.exists()) file.copyTo(backup)
                val lines = file.readLines()
                var dirty = false
                val out = lines.map { line ->
                    val key = line.substringBefore(':', "")
                    val value = preset[key]
                    if (value != null && line != "$key:$value") { dirty = true; "$key:$value" } else line
                }
                if (dirty) { file.writeText(out.joinToString("\n") + "\n"); changed++ }
            } catch (_: Throwable) {}
        }
        return changed
    }

    /** Restores every backed-up options.txt; returns how many were restored. */
    fun restore(context: Context): Int {
        var restored = 0
        for (file in optionFiles(context)) {
            val backup = File(file.path + BACKUP_SUFFIX)
            if (!backup.exists()) continue
            try { backup.copyTo(file, overwrite = true); backup.delete(); restored++ } catch (_: Throwable) {}
        }
        return restored
    }
}
