package me.nuoyuan.carpetbaritoneintegration.network;

import baritone.api.Settings;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SensitiveSettingTest {
    @Test
    public void apiKeyIsNeverEncodedIntoS2cSettings() throws Exception {
        Field field = Settings.class.getField("llmApiKey");
        String secret = "sk-test-secret-must-not-leave-server";

        String encoded = SettingOptions.encodeForClient(field, secret);

        assertEquals("<已配置>", encoded);
        assertFalse(encoded.contains(secret));
        assertEquals("<未配置>",
                SettingOptions.encodeForClient(field, ""));
    }
}
