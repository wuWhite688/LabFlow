package com.arthur.labops.workorder;

public enum WorkOrderCategory {
    /** A real equipment fault, reported by a person. */
    FAULT,
    /** Raised by the reconciliation job when local books and channel bill disagree. */
    PAYMENT_DISCREPANCY
}
