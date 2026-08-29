package com.arthur.labops.payment.reconcile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.SystemAccountInitializer;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;

/**
 * Turns reconciliation findings into work orders, reusing the fault work-order
 * table rather than growing a second ticketing system.
 *
 * <p>A discrepancy ticket never sets {@code equipmentTakenOffline}: the books
 * disagreeing says nothing about whether the equipment works, and driving it to
 * MAINTENANCE would cancel innocent reservations over an accounting problem.
 */
@Service
public class PaymentDiscrepancyTicketService {

    private static final Logger log = LoggerFactory.getLogger(PaymentDiscrepancyTicketService.class);

    private final FaultWorkOrderRepository workOrderRepository;
    private final PaymentOrderRepository orderRepository;
    private final EquipmentRepository equipmentRepository;
    private final PlatformUserRepository userRepository;
    private final AuditLogService auditLogService;

    public PaymentDiscrepancyTicketService(FaultWorkOrderRepository workOrderRepository,
                                           PaymentOrderRepository orderRepository,
                                           EquipmentRepository equipmentRepository,
                                           PlatformUserRepository userRepository,
                                           AuditLogService auditLogService) {
        this.workOrderRepository = workOrderRepository;
        this.orderRepository = orderRepository;
        this.equipmentRepository = equipmentRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Own transaction per ticket: one unmappable discrepancy (an order number the
     * platform has never seen) must not roll back the tickets already raised for
     * the rest of the bill.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raiseTicket(ReconciliationDiscrepancy discrepancy) {
        PaymentOrder order = orderRepository.findByOrderNo(discrepancy.orderNo()).orElse(null);
        if (order == null) {
            // The channel settled an order number this platform never issued. There
            // is no equipment to hang a ticket on, so the audit log is the record.
            auditLogService.recordSystem("RECONCILIATION_UNKNOWN_ORDER", "PAYMENT", null,
                    "渠道账单出现未知订单号 " + discrepancy.orderNo() + "：" + discrepancy.detail());
            log.warn("Reconciliation found unknown order in channel bill orderNo={}", discrepancy.orderNo());
            return false;
        }
        Equipment equipment = equipmentRepository.findById(order.getEquipmentId()).orElse(null);
        if (equipment == null) {
            auditLogService.recordSystem("RECONCILIATION_UNKNOWN_EQUIPMENT", "PAYMENT", order.getReservationId(),
                    "订单 " + discrepancy.orderNo() + " 对应设备不存在：" + discrepancy.detail());
            return false;
        }

        // Fast path for the common case; the unique index below is what actually
        // guarantees one ticket per finding, and the caller handles its violation.
        if (workOrderRepository.existsByDiscrepancyKey(discrepancy.key())) {
            log.debug("Discrepancy ticket already exists key={}", discrepancy.key());
            return false;
        }
        PlatformUser systemReporter = userRepository.findByUsername(SystemAccountInitializer.SYSTEM_USERNAME)
                .orElseThrow(() -> new IllegalStateException("系统账号缺失，无法创建对账差异工单"));

        FaultWorkOrder ticket = FaultWorkOrder.discrepancy(
                equipment,
                systemReporter.getId(),
                discrepancy.key(),
                "[对账差异] 订单 " + discrepancy.orderNo(),
                discrepancy.settlementDate() + " 账单对账不平（" + discrepancy.type().name() + "）："
                        + discrepancy.detail() + "，差额 " + discrepancy.deltaCents() + " 分。",
                priorityFor(discrepancy));
        workOrderRepository.saveAndFlush(ticket);
        auditLogService.recordSystem("RECONCILIATION_DISCREPANCY", "WORK_ORDER", ticket.getId(),
                "对账差异工单：" + discrepancy.key());
        return true;
    }

    /**
     * A request the channel never accepted, past its retry budget. Real money is
     * owed or unclaimed and no automated path is left, so it becomes a ticket
     * keyed by the request's own idempotency key — one ticket per stuck intent,
     * however many times the job retries afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raiseOutboundFailureTicket(com.arthur.labops.payment.PaymentRequest request) {
        return raiseOutboundHaltedTicket(request, request.getType() + " 请求重试 " + request.getAttempts()
                + " 次仍未被渠道接受，最后错误：" + request.getLastError());
    }

    /**
     * An outbound request that has stopped for a reason no retry can resolve.
     *
     * <p>Retries running out is one such reason; the amount on the request no
     * longer matching what the order owes is another, and a worse one — the
     * platform is not entitled to guess whether an operator's out-of-band refund
     * was meant to replace the one it queued. Both share the request's own key, so
     * an intent that stalls twice still produces one ticket.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean raiseOutboundHaltedTicket(com.arthur.labops.payment.PaymentRequest request, String reason) {
        String key = "outbound|" + request.getIdempotencyKey();
        if (workOrderRepository.existsByDiscrepancyKey(key)) {
            return false;
        }
        PaymentOrder order = orderRepository.findByOrderNo(request.getOrderNo()).orElse(null);
        Equipment equipment = order == null
                ? null
                : equipmentRepository.findById(order.getEquipmentId()).orElse(null);
        if (equipment == null) {
            auditLogService.recordSystem("PAYMENT_DISPATCH_ABANDONED", "PAYMENT", null,
                    "出站支付请求重试耗尽且无法定位设备：" + request.getIdempotencyKey());
            return false;
        }
        PlatformUser systemReporter = userRepository.findByUsername(SystemAccountInitializer.SYSTEM_USERNAME)
                .orElseThrow(() -> new IllegalStateException("系统账号缺失，无法创建对账差异工单"));
        FaultWorkOrder ticket = FaultWorkOrder.discrepancy(
                equipment,
                systemReporter.getId(),
                key,
                "[对账差异] 出站请求失败 " + request.getOrderNo(),
                request.getType() + " 请求金额 " + request.getAmountCents() + " 分未能完成：" + reason,
                WorkOrderPriority.HIGH);
        workOrderRepository.saveAndFlush(ticket);
        auditLogService.recordSystem("PAYMENT_DISPATCH_ABANDONED", "WORK_ORDER", ticket.getId(),
                "出站支付请求重试耗尽：" + request.getIdempotencyKey());
        return true;
    }

    private WorkOrderPriority priorityFor(ReconciliationDiscrepancy discrepancy) {
        long magnitude = Math.abs(discrepancy.deltaCents());
        if (discrepancy.type() == DiscrepancyType.MISSING_LOCALLY || magnitude >= 10_000L) {
            return WorkOrderPriority.HIGH;
        }
        return WorkOrderPriority.MEDIUM;
    }
}
