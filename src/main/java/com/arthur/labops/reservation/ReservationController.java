package com.arthur.labops.reservation;

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
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationApplicationService reservationApplicationService;

    public ReservationController(ReservationService reservationService,
                                 ReservationApplicationService reservationApplicationService) {
        this.reservationService = reservationService;
        this.reservationApplicationService = reservationApplicationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReservationResponse create(@Valid @RequestBody CreateReservationRequest request) {
        return reservationApplicationService.create(request);
    }

    @GetMapping
    PageResponse<ReservationResponse> findAll(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) Long equipmentId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(reservationService.findAll(status, equipmentId, pageable));
    }

    @PatchMapping("/{id}/decision")
    ReservationResponse decide(@PathVariable Long id,
                               @Valid @RequestBody ReservationDecisionRequest request) {
        return reservationService.decide(id, request);
    }

    @PatchMapping("/{id}/cancel")
    ReservationResponse cancel(@PathVariable Long id) {
        return reservationService.cancel(id);
    }

    @PatchMapping("/{id}/complete")
    ReservationResponse complete(@PathVariable Long id) {
        return reservationService.complete(id);
    }
}
