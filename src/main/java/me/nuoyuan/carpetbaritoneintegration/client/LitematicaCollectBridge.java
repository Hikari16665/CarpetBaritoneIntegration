package me.nuoyuan.carpetbaritoneintegration.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Reflection-only bridge so Litematica and MaLiLib remain optional. */
public final class LitematicaCollectBridge {
    private LitematicaCollectBridge() { }

    public static void addEntryButton(Object widget) {
        try {
            Object entry = field(widget, "entry");
            // Litematica represents the column header using the same widget
            // class, but deliberately passes a null material entry.
            if (entry == null) return;
            MultiItemCommandScreen.Entry requested = toEntry(entry);
            int x = (int) invoke(widget, "getX");
            int y = (int) invoke(widget, "getY");
            int width = (int) invoke(widget, "getWidth");
            ReflectedButton button = button(
                    Math.max(x + 4, x + width - 120),
                    y + 2, 52, 18,
                    "收集", () -> open(List.of(requested)));
            addButton(widget, button);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional compatibility: a changed Litematica layout is skipped.
        }
    }

    public static void addCollectAllButton(Object gui) {
        try {
            int width = (int) invoke(gui, "getScreenWidth");
            int height = (int) invoke(gui, "getScreenHeight");
            ReflectedButton button = button(width - 112, height - 26, 104, 20,
                    "全部收集", () -> open(entries(gui)));
            addButton(gui, button);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional compatibility: never prevent the material list opening.
        }
    }

    private static List<MultiItemCommandScreen.Entry> entries(Object gui) {
        List<MultiItemCommandScreen.Entry> result = new ArrayList<>();
        try {
            Object materialList = invoke(gui, "getMaterialList");
            Object values = invoke(materialList, "getMaterialsMissingOnly",
                    boolean.class, true);
            if (values instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    try {
                        if (entry == null) continue;
                        MultiItemCommandScreen.Entry converted =
                                toEntry(entry);
                        if (converted.amount() > 0) result.add(converted);
                    } catch (ReflectiveOperationException
                             | RuntimeException ignored) { }
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        return result;
    }

    private static MultiItemCommandScreen.Entry toEntry(Object entry)
            throws ReflectiveOperationException {
        ItemStack stack = (ItemStack) invoke(entry, "getStack");
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Empty material-list entry");
        }
        int missing = Math.max(1, (int) invoke(entry, "getCountMissing"));
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String value = id.getNamespace().equals("minecraft")
                ? id.getPath() : id.toString();
        return new MultiItemCommandScreen.Entry(value, missing);
    }

    private static void open(List<MultiItemCommandScreen.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.gui.screen();
        minecraft.gui.setScreen(new MultiItemCommandScreen(parent,
                BaritoneControlScreen.ControlCommand.COLLECT_ITEM, entries));
    }

    private static ReflectedButton button(
            int x, int y, int width, int height,
            String label, Runnable action)
            throws ReflectiveOperationException {
        Class<?> buttonClass = Class.forName(
                "fi.dy.masa.malilib.gui.button.ButtonGeneric");
        Object button = buttonClass
                .getConstructor(int.class, int.class, int.class, int.class,
                        String.class, String[].class)
                .newInstance(x, y, width, height, label, new String[0]);
        Class<?> listener = Class.forName(
                "fi.dy.masa.malilib.gui.button.IButtonActionListener");
        Object proxy = Proxy.newProxyInstance(listener.getClassLoader(),
                new Class<?>[]{listener}, (ignored, method, args) -> {
                    if (method.getName().equals("actionPerformedWithButton")) {
                        action.run();
                    }
                    return null;
                });
        return new ReflectedButton(button, proxy);
    }

    private static void addButton(Object owner, ReflectedButton button)
            throws ReflectiveOperationException {
        Class<?> listener = Class.forName(
                "fi.dy.masa.malilib.gui.button.IButtonActionListener");
        Class<?> buttonBase = Class.forName(
                "fi.dy.masa.malilib.gui.button.ButtonBase");
        Method method = find(owner.getClass(), "addButton",
                buttonBase, listener);
        method.setAccessible(true);
        method.invoke(owner, button.button(), button.listener());
    }

    private static Object field(Object owner, String name)
            throws ReflectiveOperationException {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method find(Class<?> type, String name, Class<?>... args)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, args);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object invoke(Object owner, String name)
            throws ReflectiveOperationException {
        Method method = find(owner.getClass(), name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object invoke(Object owner, String name,
                                 Class<?> parameter, Object value)
            throws ReflectiveOperationException {
        Method method = find(owner.getClass(), name, parameter);
        method.setAccessible(true);
        return method.invoke(owner, value);
    }

    private record ReflectedButton(Object button, Object listener) { }
}
