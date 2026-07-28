package me.nuoyuan.carpetbaritoneintegration.client;

import org.junit.Test;

/** Guards Litematica's null-entry header widget regression. */
public class LitematicaCollectBridgeTest {
    @Test
    public void headerEntryDoesNotCrash() {
        LitematicaCollectBridge.addEntryButton(new HeaderWidget());
    }

    @Test
    public void missingWidgetDoesNotCrash() {
        LitematicaCollectBridge.addEntryButton(null);
    }

    private static final class HeaderWidget {
        @SuppressWarnings("unused")
        private final Object entry = null;
    }
}
