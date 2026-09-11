package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.data.*;
import com.mystipixel.royaltrade.hooks.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TradeManagerTest {
    @TempDir Path folder;
    EconomyHook economy;
    Escrow escrow;
    TradeLog log;
    EconGuardHook guard;
    TradeManager manager;
    Player a, b;
    TradeSession session;
    MockedStatic<Bukkit> bukkit;
    PaymentJournal journal;

    @BeforeEach void setup() {
        economy = mock(EconomyHook.class); escrow = mock(Escrow.class);
        log = mock(TradeLog.class); guard = mock(EconGuardHook.class);
        journal = new PaymentJournal(folder.resolve("payments"));
        manager = new TradeManager(economy, escrow, log, guard, journal, java.util.logging.Logger.getLogger("test"));
        a = player("a"); b = player("b");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(a.getUniqueId())).thenReturn(a);
        bukkit.when(() -> Bukkit.getPlayer(b.getUniqueId())).thenReturn(b);
        Inventory probe = mock(Inventory.class);
        when(probe.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        bukkit.when(() -> Bukkit.createInventory(null, 36)).thenReturn(probe);
        when(economy.has(any(), anyDouble())).thenReturn(true);
        when(economy.withdraw(any(), anyDouble())).thenReturn(true);
        when(economy.deposit(any(), anyDouble())).thenReturn(true);
        session = manager.open(a, b);
        session.setCoins(session.a(), 10); session.setCoins(session.b(), 20);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        // Fixture starts after the UI has escrowed the item; no Paper material registry is booted.
        try {
            var field = TradeSession.Side.class.getDeclaredField("offered");
            field.setAccessible(true);
            ((List<ItemStack>) field.get(session.a())).add(item);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        session.beginSettling(1);
    }
    @AfterEach void close() { bukkit.close(); }
    private Player player(String name) {
        Player p = mock(Player.class); UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id); when(p.getName()).thenReturn(name);
        when(p.isOnline()).thenReturn(true);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(p.getInventory()).thenReturn(inv);
        when(inv.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inv.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        return p;
    }
    @Test void recipientRejectionDoesNotCompleteOrDeliverItems() {
        when(economy.deposit(a, 20)).thenReturn(false);
        assertNotEquals(TradeManager.Failure.NONE, manager.commit(session));
        assertFalse(session.finished());
        assertEquals(1, session.a().offered().size());
        verify(b.getInventory(), never()).addItem(any(ItemStack[].class));
        verifyNoInteractions(log, guard);
    }
    @Test void failedRefundCannotBeRetriedAsANewChargeOrCancelledForItems() {
        when(economy.withdraw(b, 20)).thenReturn(false);
        when(economy.deposit(a, 10)).thenReturn(false);
        assertNotEquals(TradeManager.Failure.NONE, manager.commit(session));
        session.abortSettling();
        assertFalse(session.editable());
        manager.cancel(session);
        assertEquals(1, session.a().offered().size());
        assertNotEquals(TradeManager.Failure.NONE, manager.commit(session));
        verify(economy, times(1)).withdraw(a, 10);
    }
    @Test void successfulSettlementPaysBothSidesAndDeliversOnce() {
        assertEquals(TradeManager.Failure.NONE, manager.commit(session));
        assertEquals(TradeSession.State.COMPLETED, session.state());
        assertNotEquals(TradeManager.Failure.NONE, manager.commit(session));
        verify(economy).withdraw(a, 10); verify(economy).withdraw(b, 20);
        verify(economy).deposit(a, 20); verify(economy).deposit(b, 10);
        verify(b.getInventory()).addItem(any(ItemStack[].class));
    }
    @Test void successfulRefundAllowsFreshAttemptWithoutDoubleCharging() {
        when(economy.withdraw(b, 20)).thenReturn(false, true);
        assertEquals(TradeManager.Failure.ECONOMY_ERROR, manager.commit(session));
        session.abortSettling(); assertTrue(session.editable());
        session.beginSettling(2);
        assertEquals(TradeManager.Failure.NONE, manager.commit(session));
        verify(economy, times(2)).withdraw(a, 10);
        verify(economy).deposit(a, 10);
        verify(economy).deposit(a, 20);
        assertTrue(journal.pending().isEmpty());
    }
    @Test void secondCreditFailureHoldsBothPlayersAcrossRestart() {
        when(economy.deposit(b, 10)).thenReturn(false);
        assertEquals(TradeManager.Failure.RECOVERY_REQUIRED, manager.commit(session));
        var record = journal.pending().values().iterator().next();
        assertEquals("SUCCESS", record.getProperty("leg.credit-a.status"));
        assertEquals("REJECTED", record.getProperty("leg.credit-b.status"));
        TradeManager restarted = new TradeManager(economy, escrow, log, guard,
                new PaymentJournal(folder.resolve("payments")), java.util.logging.Logger.getLogger("test"));
        assertTrue(restarted.needsRecovery(a)); assertTrue(restarted.inTrade(b));
        assertThrows(IllegalStateException.class, () -> restarted.open(a, b));
        verify(escrow, never()).release(any());
    }
    @Test void thrownProviderOutcomeIsHeldWithoutBlindRefundOrRetry() {
        when(economy.withdraw(a, 10)).thenThrow(new IllegalStateException("unknown outcome"));
        assertEquals(TradeManager.Failure.RECOVERY_REQUIRED, manager.commit(session));
        assertEquals("IN_FLIGHT", journal.pending().values().iterator().next().getProperty("leg.debit-a.status"));
        manager.cancel(session); manager.commit(session);
        verify(economy).withdraw(a, 10);
        verify(economy, never()).deposit(any(), anyDouble());
        assertEquals(1, session.a().offered().size());
    }
    @Test void failedItemDeliveryRetainsReceiptAndEscrowInsteadOfReplaying() {
        when(b.getInventory().addItem(any(ItemStack[].class))).thenThrow(new IllegalStateException("inventory failure"));
        assertEquals(TradeManager.Failure.RECOVERY_REQUIRED, manager.commit(session));
        assertEquals("IN_FLIGHT", journal.pending().values().iterator().next().getProperty("delivery"));
        verify(escrow, never()).release(any());
        assertTrue(manager.needsRecovery(a));
    }
    @Test void nonfiniteCoinOffersAreRejected() {
        session.abortSettling();
        assertFalse(session.setCoins(session.a(), Double.NaN));
        assertFalse(session.setCoins(session.a(), Double.POSITIVE_INFINITY));
    }

    @Test void unregisteredSessionCannotStartAnUnrecoverablePayment() {
        TradeSession unregistered = new TradeSession(a, b, 1);
        unregistered.setCoins(unregistered.a(), 10); unregistered.beginSettling(2);
        assertEquals(TradeManager.Failure.NOT_SETTLING, manager.commit(unregistered));
        verifyNoInteractions(economy);
        assertTrue(journal.pending().isEmpty());
    }

}
