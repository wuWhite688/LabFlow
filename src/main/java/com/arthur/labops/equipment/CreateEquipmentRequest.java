package com.arthur.labops.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateEquipmentRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 50) String category,
        @NotBlank @Size(max = 120) String location,
        @Size(max = 100) String manufacturer,
        @Size(max = 100) String model,
        @Size(max = 80) String responsiblePerson,
        LocalDate purchaseDate,
        @Size(max = 500) String description
) {
    public CreateEquipmentRequest(String code, String name, String category, String location) {
        this(code, name, category, location, null, null, null, null, null);
    }
}
