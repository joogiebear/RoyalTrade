package com.mystipixel.royaltrade.hooks;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks RoyalSkyblock to save a player's profile right after a trade, so a crash before its next autosave
 * can't restore the pre-trade inventory on one side. Wired by reflection against
 * {@code RoyalSkyblockApi.savePlayerNow(UUID)}, so there is no build-time dependency. Any failure logs one
 * warning and turns the hook off until RoyalSkyblock is enabled, disabled or RoyalTrade is reloaded.
 */
public final class SkyblockSaveHook implements Listener {

    static final String PLUGIN = "RoyalSkyblock";
    static final String API_CLASS = "com.mystipixel.royalskyblock.api.RoyalSkyblockApi";
    static final String METHOD = "savePlayerNow";

    // returns null when RoyalSkyblock isn't enabled, which is not a failure
    @FunctionalInterface
    interface ApiSource {
        Class<?> load() throws ClassNotFoundException;
    }

    private final Logger logger;
    private final ApiSource source;

    private boolean resolved;
    private Method save;

    public SkyblockSaveHook(Logger logger) {
        this(logger, SkyblockSaveHook::loadFromServer);
    }

    SkyblockSaveHook(Logger logger, ApiSource source) {
        this.logger = logger;
        this.source = source;
    }

    // the plugin's own class loader: the API class is found even if Paper restricts cross-plugin lookups
    private static Class<?> loadFromServer() throws ClassNotFoundException {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        return Class.forName(API_CLASS, true, plugin.getClass().getClassLoader());
    }

    /** Save each player's RoyalSkyblock profile now. Never throws; does nothing when the hook is off. */
    public void saveNow(UUID... players) {
        Method method = method();
        if (method == null) {
            return;
        }
        for (UUID player : players) {
            try {
                method.invoke(null, player);
            } catch (InvocationTargetException e) {
                disable("RoyalSkyblockApi." + METHOD + " threw", e.getCause());
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                disable("Could not call RoyalSkyblockApi." + METHOD, e);
                return;
            }
        }
    }

    /** Whether the next call will reach RoyalSkyblock. Resolves the method if it hasn't been yet. */
    public boolean isActive() {
        return method() != null;
    }

    /** Look the method up again on the next call; also clears a hook turned off by an earlier failure. */
    public void reset() {
        resolved = false;
        save = null;
    }

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        if (PLUGIN.equals(event.getPlugin().getName())) {
            reset();
        }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (PLUGIN.equals(event.getPlugin().getName())) {
            reset();
        }
    }

    private Method method() {
        if (resolved) {
            return save;
        }
        resolved = true;
        save = null;
        Class<?> api;
        try {
            api = source.load();
        } catch (ClassNotFoundException | LinkageError e) {
            warn("RoyalSkyblock is enabled but has no " + API_CLASS + "; profiles won't be saved after trades.", e);
            return null;
        }
        if (api == null) {
            return null;
        }
        try {
            Method found = api.getMethod(METHOD, UUID.class);
            if (!Modifier.isStatic(found.getModifiers())) {
                warn("RoyalSkyblockApi." + METHOD + " is not static; profiles won't be saved after trades.", null);
                return null;
            }
            save = found;
        } catch (NoSuchMethodException e) {
            warn("This RoyalSkyblock has no RoyalSkyblockApi." + METHOD + "(UUID) (needs 2026.41.2 or newer);"
                    + " profiles won't be saved after trades.", null);
        }
        return save;
    }

    private void disable(String what, Throwable cause) {
        save = null;
        warn(what + "; profiles won't be saved after trades until RoyalSkyblock or RoyalTrade is reloaded.", cause);
    }

    private void warn(String message, Throwable cause) {
        if (cause == null) {
            logger.warning(message);
        } else {
            logger.log(Level.WARNING, message, cause);
        }
    }
}
