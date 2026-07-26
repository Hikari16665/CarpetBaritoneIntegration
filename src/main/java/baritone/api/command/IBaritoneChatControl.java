package baritone.api.command;

import java.util.UUID;

public interface IBaritoneChatControl {
    String FORCE_COMMAND_PREFIX = "<<"
            + UUID.randomUUID() + ">>";
}
