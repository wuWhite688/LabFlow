package com.arthur.labops.payment.reconcile;

public enum DiscrepancyType {
    /** The channel settled money the local ledger has no record of. */
    MISSING_LOCALLY,
    /** The local ledger recorded money the channel's bill does not show. */
    MISSING_IN_CHANNEL,
    /** Both sides know the order, but the net amounts disagree. */
    AMOUNT_MISMATCH,
    /** Amounts agree, but the order's local state contradicts what the channel settled. */
    STATUS_MISMATCH
}
