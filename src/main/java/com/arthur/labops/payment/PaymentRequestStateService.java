package com.arthur.labops.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the outcome of one outbound send attempt in a fresh transaction.
 *
 * <p>The channel call itself runs with no database transaction open. Completion is
 * conditional on the channel key that was actually sent: a synchronous callback
 * can reject attempt #0 and advance the request to #1 before the #0 call returns,
 * and #0 must not then overwrite that newer state as SENT or FAILED.
 */
@Service
public class PaymentRequestStateService {

    private final PaymentRequestRepository requestRepository;

    public PaymentRequestStateService(PaymentRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(String idempotencyKey, String expectedChannelKey) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null || request.isSettled() || !request.matchesChannelKey(expectedChannelKey)) {
            return false;
        }
        request.markSent();
        return true;
    }

    /** @return true when the intent was still pending and has now been dropped */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markObsolete(String idempotencyKey) {
        return requestRepository.findByIdempotencyKey(idempotencyKey)
                .map(PaymentRequest::markObsolete)
                .orElse(false);
    }

    /** @return the request if this failure exhausted its retry budget, otherwise null */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentRequest markFailed(String idempotencyKey, String expectedChannelKey, String error) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null || request.isSettled() || !request.matchesChannelKey(expectedChannelKey)) {
            return null;
        }
        return request.markFailed(error) ? request : null;
    }
}
