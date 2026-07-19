# RoyalTrade

Player-to-player trading that cannot be changed after both sides agree.

Two players put items and coins on the table, both confirm, and the goods change hands. The whole
plugin exists to close one attack: agreeing to a deal and then altering it before it commits.

---

## How it refuses to be scammed

**Any change clears both confirmations.** Touch anything — add an item, remove one, change the coin
offer — and both sides go back to unconfirmed. This is the rule that matters. The classic scam is to
confirm, wait for the other player to confirm, then swap a diamond block for a renamed dirt block a
tick before the trade completes. With this rule that attack does not exist.

Both confirmations clear, not just the one belonging to whoever made the change. A player who has
confirmed agreed to a *specific* deal; the deal is now a different one, and their agreement should
not survive it.

**A settle window.** Once both sides confirm, the trade freezes for a few seconds and refuses edits
entirely. It can only complete or be cancelled. Without it, an edit landing in the same tick as the
commit is a race, and races are where duplication bugs live. The pause is also the last chance either
player has to notice something is wrong.

**Escrow, not references.** Offered items leave your inventory and are held by the trade. That is the
only way to stop the same item being offered in two trades at once — a design that merely *points* at
inventory slots cannot.

**One synchronous commit.** Verify both sides still have everything, check both have room, take both
payments, hand out both halves. All in a single main-thread block: no scheduling, no async economy
call, nothing that can interleave. Every reason a trade can fail is checked *before* the first
mutation, so once goods start moving the transfer runs to the end.

**Escrow survives a crash.** While a trade is open the items are in nobody's inventory. If the
process dies there, they exist only in memory. So escrow is written to disk on every change and
cleared once the items are somewhere real again; anything still on disk at boot is returned to its
owner on their next login.

---

## Configuration

```yaml
settle-seconds: 3            # freeze after both confirm, before the goods move
request-expiry-seconds: 60
request-cooldown-seconds: 5

same-world-only: true        # refuse cross-world trades
max-distance: -1             # blocks; -1 disables
min-playtime-hours: 0        # refuse trades involving very new accounts; 0 disables
```

The protections above are not configurable. A trade window with the safety off is worse than no trade
window, because players trust it.

`same-world-only`, `max-distance` and `min-playtime-hours` are anti-abuse settings as much as
convenience ones. Requiring players to stand together makes selling for real money less convenient,
and a playtime floor makes a throwaway alt created to receive a main account's wealth cost something.

---

## Commands

```
/trade <player>     Send a request, or accept one already waiting from that player
/trade reload       Reload config and messages          (royaltrade.admin)
```

One command does both halves deliberately. The second player types what the first player typed, so
there is no separate accept command that could land on a trade they did not mean to join.

---

## Integrations

**Vault** supplies the money. Without it items still trade and coin offers are refused — never
treated as free.

**EconGuard** is reported to on every completed trade, with both counterparties named. This matters
more here than anywhere else in the suite: EconGuard watches for wealth moving to young accounts and
for unusual velocity, which is the shape real-money trading and alt-funnelling make. Player-to-player
trade is the most direct way to move wealth between accounts, so a trade plugin that does not report
is the hole every other check gets routed around.

---

## Audit

Every completed trade is appended to `trades.log`: timestamp, both players and UUIDs, every stack
with its custom name, and both coin amounts.

A flat file rather than a database on purpose. The question this answers is "what did these two trade
on Tuesday", asked days later by an admin, and `grep` answers it without a query tool or a
dependency.
