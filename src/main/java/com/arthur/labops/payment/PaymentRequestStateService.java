package com.arthur.labops.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the outcome of a send attempt.
 *
 * <p>A separate bean on purpose: {@code PaymentDispatchService.attempt} runs with
 * no transaction open while it talks to the channel, so calling these on itself
 * would be a self-invocation — the proxy would be bypassed, no transaction would
 * start, and the entity mutation would be silently thrown away on a detached
 * instance.
 */
@Service
public class PaymentRequestStateService {

    private final PaymentRequestRepository requestRepository;

    public PaymentRequestStateService(PaymentRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(String idempotencyKey) {
        requestRepository.findByIdempotencyKey(idempotencyKey).ifPresent(PaymentRequest::markSent);
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
    public PaymentRequest markFailed(String idempotencyKey, String error) {
        PaymentRequest request = requestRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (request == null) {
            return null;
        }
        return request.markFailed(error) ? request : null;
    }
}
