package me.nuoyuan.carpetbaritoneintegration.network;

import baritone.api.Settings;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class SettingPresentationTest {
    @Test
    public void everySettingHasChinesePresentation() {
        List<String> untranslated = new ArrayList<>();
        for (Field field : Settings.class.getFields()) {
            if (field.getType() != Settings.Setting.class) continue;
            String name = SettingPresentation.name(field.getName());
            if (name.isBlank() || name.matches(".*[a-z]{2,}.*")) {
                untranslated.add(field.getName() + "=" + name);
            }
            assertTrue(SettingPresentation.description(field.getName())
                    .length() >= 12);
            assertTrue(!SettingPresentation.category(field.getName())
                    .isBlank());
        }
        assertTrue("Untranslated setting labels: " + untranslated,
                untranslated.isEmpty());
    }
}
