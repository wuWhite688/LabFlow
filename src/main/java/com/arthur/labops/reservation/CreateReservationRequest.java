package com.arthur.labops.reservation;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(
        @NotNull @Positive Long equipmentId,
        @NotBlank @Size(max = 500) String purpose,
        @NotNull @Future Instant startTime,
        @NotNull @Future Instant endTime
) {
}
