package com.arthur.labops.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.OptimisticLockException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception) {
        ApiError body = new ApiError(
                Instant.now(),
                exception.getStatus().value(),
                exception.getCode(),
                exception.getMessage(),
                Map.of());
        return ResponseEntity.status(exception.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "请求参数校验失败",
                fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "当前角色无权执行该操作",
                Map.of());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception) {
        log.warn("Data integrity violation", exception);
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "DATA_CONFLICT",
                "数据与现有记录冲突",
                Map.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * PESSIMISTIC_WRITE timeouts and {@code @Version} lost updates both become 409.
     * Reservation writers: decide, cancel, complete, expiry, work-order cancellation.
     * Work-order writers: claim, transition. Spring wraps Hibernate stale-state as
     * {@link ObjectOptimisticLockingFailureException}; a raw JPA
     * {@link OptimisticLockException} is mapped the same way if it reaches MVC.
     */
    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            OptimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    ResponseEntity<ApiError> handleLockFailure(RuntimeException exception) {
        log.warn("Lock failure", exception);
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.CONFLICT.value(),
                "RESOURCE_BUSY",
                "资源正被占用，请稍后重试",
                Map.of());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled server exception", exception);
        ApiError body = new ApiError(
                Instant.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试",
                Map.of());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
