package com.arthur.labops.reservation;

import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;

final class ReservationAlreadyExpiredException extends BusinessException {

    ReservationAlreadyExpiredException() {
        super(
                "RESERVATION_ALREADY_EXPIRED",
                "预约已超过审批时限，请重新提交",
                HttpStatus.CONFLICT);
    }
}
