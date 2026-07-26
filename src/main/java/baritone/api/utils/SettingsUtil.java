/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import baritone.api.BaritoneAPI;

/**
 * Server-safe subset of the settings utilities.
 */
public final class SettingsUtil {

    private SettingsUtil() {
    }

    public static String maybeCensor(int coordinate) {
        return BaritoneAPI.getSettings().censorCoordinates.value
                ? "<censored>"
                : Integer.toString(coordinate);
    }
}
