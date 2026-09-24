package com.mystipixel.royaltrade.trade;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeSessionTest {
    TradeSession session;

    private static Player player(String name) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn(name);
        return p;
    }
    @BeforeEach void setup() {
        session = new TradeSession(player("a"), player("b"), 1);
    }

    @Test void untouchedTradeCanBeConfirmedAtOnce() {
        assertEquals(0, session.confirmBlockedFor(System.currentTimeMillis(), 2000));
    }
    @Test void anyChangeBlocksConfirmingForTheDelay() {
        long before = System.currentTimeMillis();
        session.setCoins(session.a(), 10);
        long after = System.currentTimeMillis();
        assertTrue(session.confirmBlockedFor(after, 2000) > 0);
        assertTrue(session.confirmBlockedFor(before + 1999, 2000) > 0);
        assertEquals(0, session.confirmBlockedFor(after + 2000, 2000));
    }
    @Test void aChangeRestartsTheDelayForBothSides() {
        session.setCoins(session.a(), 10);
        long first = session.changedAt();
        session.setCoins(session.b(), 5);
        assertTrue(session.changedAt() >= first);
        assertTrue(session.confirmBlockedFor(session.changedAt() + 1000, 2000) > 0);
    }
}
