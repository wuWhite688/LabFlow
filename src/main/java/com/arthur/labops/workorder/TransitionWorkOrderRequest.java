package com.arthur.labops.workorder;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransitionWorkOrderRequest(
        @NotNull WorkOrderStatus targetStatus,
        @Positive Long assigneeId
) {
}
