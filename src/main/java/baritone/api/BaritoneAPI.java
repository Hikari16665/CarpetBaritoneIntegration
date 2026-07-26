/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api;

/**
 * Server-safe API entry point.
 *
 * <p>The provider will be attached when the per-fake-player instance manager
 * is rebuilt. Settings are already available to the pathing model.</p>
 */
public final class BaritoneAPI {

    private static final Settings SETTINGS = new Settings();
    private static volatile IBaritoneProvider provider;

    private BaritoneAPI() {
    }

    public static Settings getSettings() {
        return SETTINGS;
    }

    public static IBaritoneProvider getProvider() {
        IBaritoneProvider current = provider;
        if (current == null) throw new IllegalStateException(
                "Baritone server provider has not been initialized");
        return current;
    }

    public static void setProvider(IBaritoneProvider provider) {
        BaritoneAPI.provider = java.util.Objects.requireNonNull(provider, "provider");
    }
}
