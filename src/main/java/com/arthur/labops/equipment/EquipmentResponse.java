package com.arthur.labops.equipment;

import java.time.LocalDate;

public record EquipmentResponse(
        Long id,
        String code,
        String name,
        String category,
        String location,
        String manufacturer,
        String model,
        String responsiblePerson,
        LocalDate purchaseDate,
        String description,
        EquipmentStatus status
) {
    static EquipmentResponse from(Equipment equipment) {
        return new EquipmentResponse(
                equipment.getId(),
                equipment.getCode(),
                equipment.getName(),
                equipment.getCategory(),
                equipment.getLocation(),
                equipment.getManufacturer(),
                equipment.getModel(),
                equipment.getResponsiblePerson(),
                equipment.getPurchaseDate(),
                equipment.getDescription(),
                equipment.getStatus());
    }
}
