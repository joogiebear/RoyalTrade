package com.mystipixel.royaltrade.hooks;

import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkyblockSaveHookTest {

    // stand-ins for RoyalSkyblockApi with the same static signature
    public static final class FakeApi {
        static final List<UUID> saved = new ArrayList<>();

        public static boolean savePlayerNow(UUID player) {
            saved.add(player);
            return true;
        }
    }

    public static final class ThrowingApi {
        static int calls;

        public static boolean savePlayerNow(UUID player) {
            calls++;
            throw new IllegalStateException("storage closed");
        }
    }

    public static final class OldApi {
        public static boolean somethingElse(UUID player) {
            return true;
        }
    }

    public static final class InstanceApi {
        public boolean savePlayerNow(UUID player) {
            return true;
        }
    }

    final Logger logger = Logger.getLogger("SkyblockSaveHookTest");
    final List<LogRecord> warnings = new ArrayList<>();
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record);
        }
        @Override public void flush() { }
        @Override public void close() { }
    };
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    @BeforeEach void setup() {
        FakeApi.saved.clear();
        ThrowingApi.calls = 0;
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
    }

    @AfterEach void tearDown() {
        logger.removeHandler(capture);
    }

    private SkyblockSaveHook hook(Class<?> api, AtomicInteger loads) {
        return new SkyblockSaveHook(logger, () -> {
            loads.incrementAndGet();
            return api;
        });
    }

    @Test void savesBothPlayersThroughTheStaticMethod() {
        AtomicInteger loads = new AtomicInteger();
        SkyblockSaveHook hook = hook(FakeApi.class, loads);
        hook.saveNow(a, b);
        hook.saveNow(b);
        assertEquals(List.of(a, b, b), FakeApi.saved);
        assertEquals(1, loads.get(), "method looked up once, not per call");
        assertTrue(warnings.isEmpty());
    }

    @Test void pluginAbsentIsSilentAndCallsNothing() {
        AtomicInteger loads = new AtomicInteger();
        SkyblockSaveHook hook = hook(null, loads);
        hook.saveNow(a, b);
        hook.saveNow(a, b);
        assertFalse(hook.isActive());
        assertEquals(1, loads.get());
        assertTrue(warnings.isEmpty());
    }

    @Test void missingMethodWarnsOnceAndTurnsOff() {
        SkyblockSaveHook hook = hook(OldApi.class, new AtomicInteger());
        hook.saveNow(a, b);
        hook.saveNow(a, b);
        assertFalse(hook.isActive());
        assertEquals(1, warnings.size());
    }

    @Test void instanceMethodIsTreatedAsMissing() {
        SkyblockSaveHook hook = hook(InstanceApi.class, new AtomicInteger());
        hook.saveNow(a);
        assertFalse(hook.isActive());
        assertEquals(1, warnings.size());
    }

    @Test void missingClassWarnsOnceAndTurnsOff() {
        SkyblockSaveHook hook = new SkyblockSaveHook(logger, () -> {
            throw new ClassNotFoundException("com.mystipixel.royalskyblock.api.RoyalSkyblockApi");
        });
        hook.saveNow(a);
        hook.saveNow(b);
        assertEquals(1, warnings.size());
    }

    @Test void throwingMethodWarnsOnceTurnsOffAndDoesNotPropagate() {
        SkyblockSaveHook hook = hook(ThrowingApi.class, new AtomicInteger());
        assertDoesNotThrow(() -> hook.saveNow(a, b));
        assertDoesNotThrow(() -> hook.saveNow(a, b));
        assertEquals(1, ThrowingApi.calls, "turned off after the first failure");
        assertEquals(1, warnings.size());
        assertInstanceOf(IllegalStateException.class, warnings.get(0).getThrown());
        assertFalse(hook.isActive());
    }

    @Test void resetLooksTheMethodUpAgainAndClearsAFailure() {
        AtomicInteger loads = new AtomicInteger();
        SkyblockSaveHook hook = hook(ThrowingApi.class, loads);
        hook.saveNow(a);
        hook.reset();
        hook.saveNow(a);
        assertEquals(2, ThrowingApi.calls);
        assertEquals(2, loads.get());
    }

    @Test void royalSkyblockEnableAndDisableTriggerAReResolve() {
        AtomicInteger loads = new AtomicInteger();
        SkyblockSaveHook hook = hook(FakeApi.class, loads);
        hook.saveNow(a);
        hook.onEnable(enabled("RoyalBank"));
        hook.saveNow(a);
        assertEquals(1, loads.get(), "another plugin's enable doesn't matter");
        hook.onDisable(disabled("RoyalSkyblock"));
        hook.saveNow(a);
        hook.onEnable(enabled("RoyalSkyblock"));
        hook.saveNow(a);
        assertEquals(3, loads.get());
    }

    // mocked: constructing the event asks Bukkit which thread it is on
    private static PluginEnableEvent enabled(String name) {
        PluginEnableEvent event = mock(PluginEnableEvent.class);
        Plugin plugin = plugin(name);
        when(event.getPlugin()).thenReturn(plugin);
        return event;
    }

    private static PluginDisableEvent disabled(String name) {
        PluginDisableEvent event = mock(PluginDisableEvent.class);
        Plugin plugin = plugin(name);
        when(event.getPlugin()).thenReturn(plugin);
        return event;
    }

    private static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }
}
