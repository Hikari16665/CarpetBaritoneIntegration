package baritone.api.cache;

import baritone.api.utils.BetterBlockPos;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public interface IWaypoint {
    String getName();
    Tag getTag();
    long getCreationTimestamp();
    BetterBlockPos getLocation();

    enum Tag {
        HOME("home", "base"), DEATH("death"), BED("bed", "spawn"), USER("user");
        public final String[] names;
        Tag(String... names) { this.names = names; }
        public String getName() { return names[0]; }
        public static Tag getByName(String name) {
            for (Tag tag : values()) for (String alias : tag.names)
                if (alias.equalsIgnoreCase(name)) return tag;
            return null;
        }
        public static String[] getAllNames() {
            Set<String> result = new HashSet<>();
            for (Tag tag : values()) result.addAll(Arrays.asList(tag.names));
            return result.toArray(String[]::new);
        }
    }
}
