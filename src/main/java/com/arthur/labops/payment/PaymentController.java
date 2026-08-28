package com.arthur.labops.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.arthur.labops.common.BusinessException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    public static final String CALLBACK_TOKEN_HEADER = "X-Channel-Token";

    private final PaymentCallbackIngest callbackIngest;
    private final PaymentQueryService queryService;
    private final PaymentProperties properties;

    public PaymentController(PaymentCallbackIngest callbackIngest,
                             PaymentQueryService queryService,
                             PaymentProperties properties) {
        this.callbackIngest = callbackIngest;
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * Channel callback endpoint. Unauthenticated in the Spring Security sense — a
     * gateway has no platform account — so the shared token stands in for the
     * signature verification a real integration would do.
     */
    @PostMapping("/callback")
    PaymentCallbackResult callback(@RequestHeader(name = CALLBACK_TOKEN_HEADER, required = false) String token,
                                   @Valid @RequestBody PaymentCallbackRequest request) {
        if (!properties.getCallbackToken().equals(token)) {
            throw new BusinessException("PAYMENT_CALLBACK_UNAUTHORIZED", "渠道回调校验失败", HttpStatus.UNAUTHORIZED);
        }
        return callbackIngest.ingest(request);
    }

    @GetMapping("/orders/{orderNo}")
    PaymentOrderResponse findOrder(@PathVariable String orderNo) {
        return queryService.findOrder(orderNo);
    }

    @PostMapping("/orders/{orderNo}/pay")
    PaymentOrderResponse pay(@PathVariable String orderNo) {
        return queryService.payThroughChannel(orderNo);
    }
}
