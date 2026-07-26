/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-safe replacement for Baritone's client chat/toast helper. */
public interface Helper {
    Helper HELPER = new Helper() {
    };
    Logger LOGGER = LoggerFactory.getLogger("Baritone");

    default void logDebug(String message) {
        LOGGER.debug(message);
    }

    default void logDirect(String message) {
        LOGGER.info(message);
    }

    default void logNotification(String message, boolean error) {
        if (error) {
            LOGGER.error(message);
        } else {
            LOGGER.info(message);
        }
    }

    default void logUnhandledException(Throwable exception) {
        LOGGER.error("Unhandled Baritone exception", exception);
    }
}
