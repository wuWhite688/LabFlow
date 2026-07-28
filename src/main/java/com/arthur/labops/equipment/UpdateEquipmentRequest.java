package com.arthur.labops.equipment;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateEquipmentRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String category,
        @NotBlank @Size(max = 120) String location,
        @Size(max = 100) String manufacturer,
        @Size(max = 100) String model,
        @Size(max = 80) String responsiblePerson,
        LocalDate purchaseDate,
        @Size(max = 500) String description
) {
}
