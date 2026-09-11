# Payment recovery

Payment receipts live in `payments/pending/<UUID>.properties`; closed receipts move to `payments/receipts/`. The leg records contain the recipient/account UUID, amount, direction, and result. Files are written using a flushed temporary file and atomic rename on the same filesystem. Unsupported atomic writes fail closed. This is not a transaction spanning Vault, player storage, and the auction database, nor a guarantee against every storage/power failure.

- `SUCCESS`: the provider acknowledged the leg. Do not pay it again.
- `REJECTED`: the provider explicitly declined it. This assumes a conforming provider did not move the money despite returning failure.
- `IN_FLIGHT`: the call or result write was interrupted; the provider may already have moved money. Never automatically replay or invert this leg.
- An intent with no leg result also remains for inspection; it is not proof of a completed transaction.

The [Vault economy API](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java) offers individual withdrawals/deposits, not a cross-provider transaction or idempotency key. A generic retry library cannot resolve an unknown payment outcome. Existing local storage is retained; no extra runtime library/service is installed.

## Operator reconciliation

Stop the server normally and back up the plugin data, player data, and economy provider's ledger. Inspect the receipt, server log, and provider history together. Determine which legs actually moved money before applying compensation. Record the resolution and preserve the original receipt; do not simply delete pending records or repeat a purchase/trade. If the provider history cannot establish an unknown leg's outcome, keep the hold and escalate instead of guessing.

## Trade behavior

Confirmed recipient credits now precede item delivery. A failed first debit can be retried through the normal confirmation UI. If the second debit is rejected, that is allowed only after the first debit's refund succeeds. A failed refund, rejected recipient credit, provider exception, or interrupted delivery puts the session in RECOVERY. Cancel, disconnect, shutdown, and restart do not automatically return its escrow or initiate another charge. Both participants remain blocked from opening another trade.

The receipt's `reason=trade:<session UUID>` identifies the matching `escrow.yml` section. Reconcile **all** debit/credit/refund legs, then determine whether items were already delivered. `delivery=IN_FLIGHT` means inventory delivery may have started, not that it completed. Do not return an entire saved offer when some items may already be in a recipient's inventory.

After staff have actually settled the money and delivered/returned the items once, remove that resolved session's escrow section (not unrelated sessions) and move its pending receipt to the receipts directory while the server is stopped. Keep a resolution note alongside the backed-up records. Restart to clear the participants' in-memory hold. There is intentionally no automatic recovery command for ambiguous money movements.

Do not downgrade to a version unaware of payment holds with pending receipts: the old startup sweep could return already-paid escrow. Resolve holds first or restore a coordinated pre-upgrade data/provider backup.

## Verification boundary

Unit tests cover the real manager with already-escrowed item fixtures, rejected debits/credits/refunds, confirmed compensation and safe retry, second-credit failure, restart holds, cancellation refusal, delivery failure, and invalid amounts. Storage tests cover atomic receipt persistence, failed writes, corruption, and held-session startup filtering. These are not proofs of exactly-once delivery across a machine crash or tests of every economy provider.
