package com.arthur.labops.reservation;

import jakarta.validation.constraints.NotNull;

public record ReservationDecisionRequest(@NotNull ReservationStatus decision) {
}
