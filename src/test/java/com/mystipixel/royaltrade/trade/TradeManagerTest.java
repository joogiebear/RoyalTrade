package com.mystipixel.royaltrade.trade;

import com.mystipixel.royaltrade.data.*;
import com.mystipixel.royaltrade.hooks.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
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
        when(guard.allow(any())).thenReturn(true);
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
        verifyNoInteractions(log);
        verify(guard, never()).observe(any(), any(), anyDouble(), anyDouble(), anyList(), anyList());
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

    @Test void econGuardVetoStopsTheTradeBeforeAnythingMoves() {
        when(guard.allow(b)).thenReturn(false);
        assertEquals(TradeManager.Failure.RESTRICTED, manager.commit(session));
        verify(economy, never()).withdraw(any(), anyDouble());
        verify(b.getInventory(), never()).addItem(any(ItemStack[].class));
        assertTrue(journal.pending().isEmpty());
        assertEquals(1, session.a().offered().size());
    }
    @Test void completedTradeIsReportedWithTheItemsEachSideGave() {
        assertEquals(TradeManager.Failure.NONE, manager.commit(session));
        verify(guard).observe(eq(a), eq(b), eq(10.0), eq(20.0), argThat(l -> l.size() == 1), argThat(List::isEmpty));
        verify(guard).observe(eq(b), eq(a), eq(20.0), eq(10.0), argThat(List::isEmpty), argThat(l -> l.size() == 1));
    }
    @Test void aSecondRequestDoesNotReplaceTheFirst() {
        Player c = player("c");
        manager.request(a, c, 60_000);
        manager.request(b, c, 60_000);
        assertTrue(manager.hasRequest(c, a, 60_000));
        assertTrue(manager.hasRequest(c, b, 60_000));
        manager.clearRequest(c, a);
        assertFalse(manager.hasRequest(c, a, 60_000));
        assertTrue(manager.hasRequest(c, b, 60_000));
    }
    @Test void expiredRequestsAreGone() throws InterruptedException {
        Player c = player("c");
        manager.request(a, c, 1);
        Thread.sleep(5);
        assertFalse(manager.hasRequest(c, a, 1));
    }
    @Test void aDepartingPlayersRequestsBothWaysAreForgotten() {
        Player c = player("c");
        manager.request(a, c, 60_000);
        manager.request(c, b, 60_000);
        manager.forgetRequests(c.getUniqueId());
        assertFalse(manager.hasRequest(c, a, 60_000));
        assertFalse(manager.hasRequest(b, c, 60_000));
    }
    @Test void completionSavesBothInventoriesBeforeReleasingEscrow() {
        assertEquals(TradeManager.Failure.NONE, manager.commit(session));
        InOrder order = inOrder(b.getInventory(), a, b, escrow);
        order.verify(b.getInventory()).addItem(any(ItemStack[].class));
        order.verify(a).saveData();
        order.verify(b).saveData();
        order.verify(escrow).release(any());
    }
    @Test void cancelSavesTheReturnedInventoryBeforeReleasingEscrow() {
        manager.cancel(session);
        InOrder order = inOrder(a.getInventory(), a, escrow);
        order.verify(a.getInventory()).addItem(any(ItemStack[].class));
        order.verify(a).saveData();
        order.verify(escrow).release(any());
    }
    @Test void persistWritesEscrowBeforeThePlayerSave() {
        manager.persist(session);
        InOrder order = inOrder(escrow, a);
        order.verify(escrow).hold(any(), anyMap());
        order.verify(a).saveData();
        verify(escrow, times(1)).hold(any(), anyMap());      // one flush for both sides
    }
    @Test void aFailedPlayerSaveDoesNotFailTheTrade() {
        doThrow(new IllegalStateException("disk full")).when(a).saveData();
        assertEquals(TradeManager.Failure.NONE, manager.commit(session));
        assertEquals(TradeSession.State.COMPLETED, session.state());
    }

}
