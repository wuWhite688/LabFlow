package com.arthur.labops.payment.reconcile;

import java.time.LocalDate;

public record ReconciliationDiscrepancy(
        LocalDate settlementDate,
        String orderNo,
        DiscrepancyType type,
        long channelNetCents,
        long localNetCents,
        String detail) {

    /**
     * Stable identity of this discrepancy, so the same disagreement found on a
     * re-run maps onto the same ticket instead of a new one.
     */
    public String key() {
        return settlementDate + "|" + orderNo + "|" + type.name();
    }

    public long deltaCents() {
        return channelNetCents - localNetCents;
    }
}
