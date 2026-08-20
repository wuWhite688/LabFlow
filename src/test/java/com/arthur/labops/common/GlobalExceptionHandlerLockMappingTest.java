package com.arthur.labops.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.arthur.labops.reservation.Reservation;

import jakarta.persistence.OptimisticLockException;

class GlobalExceptionHandlerLockMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void pessimisticAndOptimisticLockFailuresMapTo409ResourceBusy() {
        assertLockMapped(new PessimisticLockingFailureException("row locked"));
        assertLockMapped(new OptimisticLockingFailureException("stale"));
        assertLockMapped(new ObjectOptimisticLockingFailureException(Reservation.class, 1L));
        assertLockMapped(new OptimisticLockException("jpa stale"));
    }

    private void assertLockMapped(RuntimeException exception) {
        ResponseEntity<ApiError> response = handler.handleLockFailure(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_BUSY");
    }
}
