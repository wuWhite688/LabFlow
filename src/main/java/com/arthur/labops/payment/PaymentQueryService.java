package com.arthur.labops.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.UserRole;

@Service
public class PaymentQueryService {

    private final PaymentOrderRepository orderRepository;
    private final SimulatedPaymentChannel channel;
    private final CurrentUserService currentUserService;

    public PaymentQueryService(PaymentOrderRepository orderRepository,
                               SimulatedPaymentChannel channel,
                               CurrentUserService currentUserService) {
        this.orderRepository = orderRepository;
        this.channel = channel;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public PaymentOrderResponse findOrder(String orderNo) {
        return PaymentOrderResponse.from(requireVisibleOrder(orderNo));
    }

    /**
     * Stands in for "the payer completes the payment in the channel's app".
     *
     * <p>Deliberately not transactional: it asks the channel to move money and the
     * ledger is only written when the callback comes back, exactly as it would be
     * with a real gateway. Holding a transaction open across that call is what
     * makes a channel that succeeded and a database that rolled back look the
     * same from the outside.
     */
    public PaymentOrderResponse payThroughChannel(String orderNo) {
        PaymentOrder order = requireVisibleOrder(orderNo);
        if (order.getStatus() != PaymentOrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException(
                    "PAYMENT_ORDER_NOT_PAYABLE", "订单当前状态不可支付", HttpStatus.CONFLICT);
        }
        channel.charge(orderNo, order.getAmountCents() - order.getPaidCents());
        return PaymentOrderResponse.from(
                orderRepository.findByOrderNo(orderNo).orElseThrow(PaymentQueryService::orderNotFound));
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
                    "PAYMENT_ORDER_NOT_OWNED", "只能查看或支付自己的订单", HttpStatus.FORBIDDEN);
        }
        return order;
    }

    private static BusinessException orderNotFound() {
        return new BusinessException("PAYMENT_ORDER_NOT_FOUND", "支付订单不存在", HttpStatus.NOT_FOUND);
    }
}
