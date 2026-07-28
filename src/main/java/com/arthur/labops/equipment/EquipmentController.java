package com.arthur.labops.equipment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.Valid;
import com.arthur.labops.common.PageResponse;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EquipmentResponse create(@Valid @RequestBody CreateEquipmentRequest request) {
        return equipmentService.create(request);
    }

    @PatchMapping("/{id}")
    EquipmentResponse update(@PathVariable Long id, @Valid @RequestBody UpdateEquipmentRequest request) {
        return equipmentService.update(id, request);
    }

    @PatchMapping("/{id}/retire")
    EquipmentResponse retire(@PathVariable Long id) {
        return equipmentService.retire(id);
    }

    @PatchMapping("/{id}/restore")
    EquipmentResponse restore(@PathVariable Long id) {
        return equipmentService.restore(id);
    }

    @GetMapping
    PageResponse<EquipmentResponse> findAll(
            @RequestParam(required = false) EquipmentStatus status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(equipmentService.findAll(status, category, keyword, pageable));
    }
}
