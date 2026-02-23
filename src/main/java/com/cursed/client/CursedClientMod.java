package com.cursed.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * CursedClient — Universal Fabric client mod.
 *
 * Works on every Fabric version from 1.18+ without any Mixins.
 * Uses GLFW directly to set the window title and pure reflection
 * for all Minecraft internals, making it version-independent.
 *
 * Title format: CursedClient 1.0|<game version>
 */
public class CursedClientMod implements ClientModInitializer {

    public static final String CLIENT_NAME = "CursedClient";
    public static final String CLIENT_VERSION = "1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("cursedclient");

    private boolean titleSet = false;
    private int ticksWaited = 0;
    private static final int MAX_WAIT_TICKS = 200; // ~10 seconds at 20 TPS

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} v{} initializing...", CLIENT_NAME, CLIENT_VERSION);

        // Use Fabric API's client tick event (stable across all Fabric versions).
        // On each tick, attempt to set the window title until successful or timeout.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (titleSet) return;

            ticksWaited++;
            if (ticksWaited > MAX_WAIT_TICKS) {
                LOGGER.warn("Gave up setting window title after {} ticks.", MAX_WAIT_TICKS);
                titleSet = true;
                return;
            }

            titleSet = trySetWindowTitle(client);
        });

        LOGGER.info("{} v{} loaded.", CLIENT_NAME, CLIENT_VERSION);
    }

    // ================================================================
    //  Window Title
    // ================================================================

    /**
     * Attempt to set the GLFW window title.
     * Uses reflection to obtain the window handle from MinecraftClient,
     * then calls GLFW.glfwSetWindowTitle() directly.
     *
     * @param mcClient the MinecraftClient instance (passed as Object to
     *                 avoid compile-time version coupling)
     * @return true if title was set successfully
     */
    private boolean trySetWindowTitle(Object mcClient) {
        try {
            long handle = getWindowHandle(mcClient);
            if (handle == 0L) return false;

            String mcVersion = detectMinecraftVersion();
            String title = CLIENT_NAME + " " + CLIENT_VERSION + "|" + mcVersion;

            GLFW.glfwSetWindowTitle(handle, title);
            LOGGER.info("Window title set: {}", title);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Title set attempt failed: {}", e.getMessage());
            return false;
        }
    }

    // ================================================================
    //  Window Handle (reflection)
    // ================================================================

    /**
     * Get the GLFW window handle from MinecraftClient via reflection.
     * Tries multiple known method/field names to cover Yarn mappings
     * and Fabric intermediary across all MC versions.
     */
    private long getWindowHandle(Object mcClient) {
        try {
            // Step 1: MinecraftClient.getWindow() → Window object
            //   Yarn:         getWindow()
            //   Intermediary: method_22683
            Object window = tryInvoke(mcClient,
                    "getWindow", "method_22683");
            if (window == null) return 0L;

            // Step 2: Window.getHandle() → long
            //   Yarn:         getHandle()
            //   Intermediary: method_4490
            Object handle = tryInvoke(window,
                    "getHandle", "method_4490");
            if (handle instanceof Long) return (Long) handle;

            // Fallback: direct field access on Window
            //   Yarn:         handle
            //   Intermediary: field_16784
            Long fieldHandle = tryGetLongField(window,
                    "handle", "field_16784");
            if (fieldHandle != null) return fieldHandle;

        } catch (Exception e) {
            LOGGER.debug("Window handle reflection error: {}", e.getMessage());
        }
        return 0L;
    }

    // ================================================================
    //  Minecraft Version Detection (reflection)
    // ================================================================

    /**
     * Detect the running Minecraft version string via reflection.
     * Tries SharedConstants.getGameVersion().getName() first,
     * then falls back to static fields for older versions.
     */
    private String detectMinecraftVersion() {
        // SharedConstants class names across mappings
        String[] classNames = {
                "net.minecraft.SharedConstants",       // Yarn (1.19.3+)
                "net.minecraft.util.SharedConstants",  // Yarn (older)
                "net.minecraft.class_155"              // Intermediary
        };

        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                String ver = tryExtractVersion(clazz);
                if (ver != null) return ver;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return "Unknown";
    }

    private String tryExtractVersion(Class<?> sharedConstants) {
        // Modern path: SharedConstants.getGameVersion().getName()
        //   getGameVersion: method_16673 (intermediary)
        //   getName:        varies (method_47563, method_16680, etc.)
        Object gameVersion = tryInvokeStatic(sharedConstants,
                "getGameVersion", "method_16673");

        if (gameVersion != null) {
            // Try common method names for GameVersion.getName()
            Object name = tryInvoke(gameVersion,
                    "getName",
                    "method_47563",  // 1.20.3+
                    "method_16680",  // 1.18-1.20.2
                    "getId",
                    "getVersionString");

            if (name instanceof String) return (String) name;

            // Last resort: toString()
            String s = gameVersion.toString();
            if (s != null && !s.isEmpty() && !s.contains("@")) return s;
        }

        // Legacy fallback: static VERSION_NAME field
        //   Yarn:         VERSION_NAME
        //   Intermediary: field_634
        String ver = tryGetStringField(sharedConstants,
                "VERSION_NAME", "field_634");
        if (ver != null) return ver;

        // Another legacy: getGameVersion() returns a String directly
        Object direct = tryInvokeStatic(sharedConstants, "getGameVersion");
        if (direct instanceof String) return (String) direct;

        return null;
    }

    // ================================================================
    //  Reflection Utilities
    // ================================================================

    /**
     * Try to invoke a no-arg method on an object instance.
     * Returns the result, or null if all candidates fail.
     */
    private static Object tryInvoke(Object obj, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = findNoArgMethod(obj.getClass(), name);
                if (m != null) {
                    m.setAccessible(true);
                    return m.invoke(obj);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Try to invoke a static no-arg method on a class.
     * Returns the result, or null if all candidates fail.
     */
    private static Object tryInvokeStatic(Class<?> clazz, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = findNoArgMethod(clazz, name);
                if (m != null) {
                    m.setAccessible(true);
                    return m.invoke(null);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Search a class hierarchy for a no-arg method by name.
     */
    private static Method findNoArgMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Try to read a long field from an object instance.
     */
    private static Long tryGetLongField(Object obj, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                Field f = findField(obj.getClass(), name);
                if (f != null) {
                    f.setAccessible(true);
                    return f.getLong(obj);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Try to read a static String field from a class.
     */
    private static String tryGetStringField(Class<?> clazz, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                Field f = findField(clazz, name);
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof String) return (String) val;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Search a class hierarchy for a field by name.
     */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
