package com.arthur.labops.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.UserRole;

@Service
public class PaymentQueryService {

    private final PaymentOrderRepository orderRepository;
    private final PaymentDispatchService dispatchService;
    private final CurrentUserService currentUserService;

    public PaymentQueryService(PaymentOrderRepository orderRepository,
                               PaymentDispatchService dispatchService,
                               CurrentUserService currentUserService) {
        this.orderRepository = orderRepository;
        this.dispatchService = dispatchService;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public PaymentOrderResponse findOrder(String orderNo) {
        return PaymentOrderResponse.from(requireVisibleOrder(orderNo));
    }

    /**
     * Stands in for "the payer completes the payment in the channel's app".
     *
     * <p>The order-status guard is not enough on its own: between asking the
     * channel to charge and the callback returning, the order is still
     * AWAITING_PAYMENT, so a second tap passes the same check and would create a
     * second real transaction with its own id — which callback idempotency cannot
     * see and reconciliation reports as balanced. The stable idempotency key on
     * the request is what actually makes this safe; the guard just gives an
     * honest 409 once the money has visibly landed.
     */
    public PaymentOrderResponse payThroughChannel(String orderNo) {
        PaymentOrder order = requirePayableOrder(orderNo);
        if (order.getStatus() != PaymentOrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException(
                    "PAYMENT_ORDER_NOT_PAYABLE", "订单当前状态不可支付", HttpStatus.CONFLICT);
        }
        dispatchService.enqueue(
                orderNo,
                PaymentTransactionType.PAYMENT,
                order.getAmountCents() - order.getPaidCents(),
                PaymentIdempotency.payment(orderNo));
        return PaymentOrderResponse.from(
                orderRepository.findByOrderNo(orderNo).orElseThrow(PaymentQueryService::orderNotFound));
    }

    /**
     * Paying is narrower than viewing. A teacher or admin has a legitimate reason
     * to look at a student's order; neither has any business moving that
     * student's money, so the payer is the only one who may trigger a charge.
     */
    @Transactional(readOnly = true)
    public PaymentOrder requirePayableOrder(String orderNo) {
        PaymentOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(PaymentQueryService::orderNotFound);
        PlatformUser actor = currentUserService.getRequiredUser();
        if (!order.getPayerId().equals(actor.getId())) {
            throw new BusinessException(
                    "PAYMENT_ORDER_NOT_OWNED", "只能支付自己的订单", HttpStatus.FORBIDDEN);
        }
        return order;
    }

    @Transactional(readOnly = true)
    public PaymentOrder requireVisibleOrder(String orderNo) {
        PaymentOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(PaymentQueryService::orderNotFound);
        PlatformUser actor = currentUserService.getRequiredUser();
        if (!order.getPayerId().equals(actor.getId())
                && actor.getRole() != UserRole.ADMIN
                && actor.getRole() != UserRole.TEACHER) {
            throw new BusinessException(
                    "PAYMENT_ORDER_NOT_VISIBLE", "只能查看自己的订单", HttpStatus.FORBIDDEN);
        }
        return order;
    }

    private static BusinessException orderNotFound() {
        return new BusinessException("PAYMENT_ORDER_NOT_FOUND", "支付订单不存在", HttpStatus.NOT_FOUND);
    }
}
