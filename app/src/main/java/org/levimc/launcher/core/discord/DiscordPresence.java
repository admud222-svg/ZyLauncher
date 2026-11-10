package org.levimc.launcher.core.discord;

import android.app.Application;

/**
 * Zy Launcher: Discord Rich Presence is disabled.
 *
 * The upstream implementation depends on the private Discord Social SDK AAR, which public forks cannot
 * download in CI. The public API is kept so callers compile unchanged; every call is a no-op.
 */
public final class DiscordPresence {
    private DiscordPresence() {}

    public static void initialize(Application application) {}

    public static boolean isConfigured() {
        return false;
    }

    public static void refresh() {}

    public static void shutdown() {}
}
