package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.RoyalTradePlugin;
import com.mystipixel.royaltrade.gui.TradeGui;
import com.mystipixel.royaltrade.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;

class TradeListenerTest {
    RoyalTradePlugin plugin;
    TradeManager trades;
    TradeListener listener;
    Player a, b;
    TradeSession session;
    MockedStatic<Bukkit> bukkit;

    private static Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        InventoryView own = mock(InventoryView.class);          // a player always has a view open
        when(own.getTopInventory()).thenReturn(mock(Inventory.class));
        when(p.getOpenInventory()).thenReturn(own);
        return p;
    }
    private static InventoryCloseEvent closeOf(Player player, Inventory window) {
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        when(view.getTopInventory()).thenReturn(window);
        return new InventoryCloseEvent(view);
    }
    @SuppressWarnings("unchecked")
    private void flagExpectedClose(Player player, Inventory window) throws ReflectiveOperationException {
        var field = TradeListener.class.getDeclaredField("expectedClose");
        field.setAccessible(true);
        ((Map<UUID, Inventory>) field.get(listener)).put(player.getUniqueId(), window);
    }

    @BeforeEach void setup() {
        plugin = mock(RoyalTradePlugin.class);
        trades = mock(TradeManager.class);
        when(plugin.trades()).thenReturn(trades);
        when(plugin.gui()).thenReturn(mock(TradeGui.class));
        when(plugin.messages()).thenReturn(mock(MessageManager.class));
        listener = new TradeListener(plugin);
        a = player("a"); b = player("b");
        session = new TradeSession(a, b, 1);
        when(trades.sessionOf(a)).thenReturn(session);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(a.getUniqueId())).thenReturn(a);
        bukkit.when(() -> Bukkit.getPlayer(b.getUniqueId())).thenReturn(b);
    }
    @AfterEach void close() { bukkit.close(); }

    @Test void closingTheWindowWeClosedDoesNotCancel() throws Exception {
        Inventory window = mock(Inventory.class);
        flagExpectedClose(a, window);
        listener.onClose(closeOf(a, window));
        verify(trades, never()).cancel(any());
    }
    @Test void aStaleFlagForAnOldWindowDoesNotSwallowANewWindowsClose() throws Exception {
        flagExpectedClose(a, mock(Inventory.class));
        listener.onClose(closeOf(a, mock(Inventory.class)));
        verify(trades).cancel(session);
    }
    @Test void theFlagIsSpentEvenWhenItDoesNotMatch() throws Exception {
        flagExpectedClose(a, mock(Inventory.class));
        listener.onClose(closeOf(a, mock(Inventory.class)));
        listener.onClose(closeOf(a, mock(Inventory.class)));
        verify(trades, times(2)).cancel(session);
    }
}
