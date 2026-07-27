package baritone.server;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class ClientControlScreenTest {
    @Test
    public void menuAndParameterScreenUseServerSelectors() throws Exception {
        Path client = Path.of("src", "main", "java", "me", "nuoyuan",
                "carpetbaritoneintegration", "client");
        String menu = Files.readString(
                client.resolve("BaritoneControlScreen.java"));
        String parameters = Files.readString(
                client.resolve("CommandParameterScreen.java"));
        String options = Files.readString(
                client.resolve("ClientControlOptions.java"));
        String structured = Files.readString(
                client.resolve("StructuredCommandScreen.java"));
        String settings = Files.readString(
                client.resolve("SettingEditorScreen.java"));
        String settingsList = Files.readString(
                client.resolve("SettingsListScreen.java"));
        String initializer = Files.readString(Path.of("src", "main", "java",
                "me", "nuoyuan", "carpetbaritoneintegration",
                "Carpetbaritoneintegration.java"));

        assertTrue(menu.contains("选择要执行的命令"));
        assertTrue(menu.contains("new CommandParameterScreen"));
        assertTrue(menu.contains("new ScrollContainerWidget"));
        assertTrue(menu.contains("scroll.addComponent"));
        assertTrue(menu.contains("RELOAD_ALL(\"reloadall\""));
        assertTrue(menu.contains("WAYPOINTS(\"waypoints\""));
        assertTrue(menu.contains("SETTINGS(\"settings\""));
        assertTrue(menu.contains("HELP(\"help\""));
        assertTrue(menu.contains("COME(\"come\""));
        assertTrue(menu.contains(
                "CLEAN(\"clean\", \"清空现有选区\", Kind.NONE"));
        assertTrue(menu.contains("new StructuredCommandScreen"));
        assertTrue(menu.contains("new SettingsListScreen"));
        assertTrue(parameters.contains("new ItemComponent"));
        assertTrue(parameters.contains("假人选择器: "));
        assertTrue(parameters.contains("玩家选择器: "));
        assertTrue(parameters.contains("\"tell \""));
        assertTrue(parameters.contains("sendTell(fake, \"pos1\""));
        assertTrue(parameters.contains("sendTell(fake, \"pos2\""));
        assertTrue(options.contains("selectedFakePlayer"));
        assertTrue(options.contains("rememberFake"));
        assertTrue(options.contains("waypoints(String fake)"));
        assertTrue(structured.contains("initWaypoint"));
        assertTrue(structured.contains("initBuild"));
        assertTrue(structured.contains("case CACHE"));
        assertTrue(settings.contains("case \"COLOR\""));
        assertTrue(settings.contains("case \"BLOCK_MAP\""));
        assertTrue(settings.contains("registryCandidates"));
        assertTrue(settingsList.contains("awaitingInitialOptions"));
        assertTrue(settingsList.contains(
                "if (!awaitingInitialOptions) return"));
        assertTrue(options.contains("ControlOptionsRequestPayload"));
        assertTrue(initializer.contains(
                ".filter(EntityPlayerMPFake.class::isInstance)"));
    }
}
