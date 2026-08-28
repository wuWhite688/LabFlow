package com.arthur.labops.payment.reconcile;

import java.time.LocalDate;
import java.util.List;

public record ReconciliationReport(
        LocalDate settlementDate,
        int comparedOrders,
        List<ReconciliationDiscrepancy> discrepancies,
        int ticketsRaised) {

    public boolean balanced() {
        return discrepancies.isEmpty();
    }
}
