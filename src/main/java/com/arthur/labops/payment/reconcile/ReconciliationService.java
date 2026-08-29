package com.arthur.labops.payment.reconcile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentOrderStatus;
import com.arthur.labops.payment.PaymentTransaction;
import com.arthur.labops.payment.PaymentTransactionRepository;
import com.arthur.labops.payment.channel.ChannelBillFile;
import com.arthur.labops.payment.channel.ChannelEntry;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

/**
 * Compares the channel's T+1 settlement file against the local ledger, order by
 * order, and turns every disagreement into a ticket.
 *
 * <p>Two decisions carry this whole class.
 *
 * <p><strong>What gets compared.</strong> The local side is the sum of the rows
 * the ledger actually recorded, not what the order was expected to collect.
 * Those two agree only while nothing has gone wrong and nothing has been
 * refunded — which is to say, exactly when reconciliation has nothing to do.
 * Comparing the expectation flags every legitimate refund, and stays silent on
 * the case that matters most: a payment the channel settled and the local side
 * never recorded, where the expectation and the channel agree precisely because
 * the ledger is the thing that is missing.
 *
 * <p><strong>Which orders get compared.</strong> The union of both sides. Walking
 * only the local rows cannot see a channel-only entry, and walking only the bill
 * cannot see money we recorded that the channel never settled.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private static final Set<PaymentOrderStatus> UNSETTLED_STATUSES =
            Set.of(PaymentOrderStatus.AWAITING_PAYMENT, PaymentOrderStatus.CLOSED);

    private final SimulatedPaymentChannel channel;
    private final PaymentOrderRepository orderRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentDiscrepancyTicketService ticketService;

    public ReconciliationService(SimulatedPaymentChannel channel,
                                 PaymentOrderRepository orderRepository,
                                 PaymentTransactionRepository transactionRepository,
                                 PaymentDiscrepancyTicketService ticketService) {
        this.channel = channel;
        this.orderRepository = orderRepository;
        this.transactionRepository = transactionRepository;
        this.ticketService = ticketService;
    }

    /**
     * Not transactional as a whole: each ticket gets its own transaction so one
     * unmappable order cannot roll back the findings already recorded.
     */
    public ReconciliationReport reconcile(LocalDate settlementDate) {
        Map<String, Long> channelNetByOrder = readChannelBill(settlementDate);
        Map<String, Long> localNetByOrder = readLocalLedger(settlementDate);

        Set<String> orderNos = new LinkedHashSet<>(channelNetByOrder.keySet());
        orderNos.addAll(localNetByOrder.keySet());

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        for (String orderNo : orderNos) {
            ReconciliationDiscrepancy discrepancy = compare(
                    settlementDate,
                    orderNo,
                    channelNetByOrder.getOrDefault(orderNo, 0L),
                    localNetByOrder.getOrDefault(orderNo, 0L));
            if (discrepancy != null) {
                discrepancies.add(discrepancy);
            }
        }

        int tickets = 0;
        for (ReconciliationDiscrepancy discrepancy : discrepancies) {
            try {
                if (ticketService.raiseTicket(discrepancy)) {
                    tickets++;
                }
            } catch (DataIntegrityViolationException duplicate) {
                // The unique index on discrepancy_key rejected a second ticket for
                // the same finding. Reconciliation is meant to be re-runnable, so a
                // concurrent or repeated run losing this race is the normal path.
                // Caught out here rather than inside the ticket transaction: a
                // transaction marked rollback-only cannot be talked out of it.
                log.debug("Discrepancy ticket already recorded key={}", discrepancy.key());
            }
        }
        log.info("Reconciliation finished date={} orders={} discrepancies={} tickets={}",
                settlementDate, orderNos.size(), discrepancies.size(), tickets);
        return new ReconciliationReport(settlementDate, orderNos.size(), List.copyOf(discrepancies), tickets);
    }

    private ReconciliationDiscrepancy compare(LocalDate settlementDate, String orderNo,
                                              long channelNet, long localNet) {
        if (channelNet != localNet) {
            DiscrepancyType type;
            String detail;
            if (localNet == 0L) {
                type = DiscrepancyType.MISSING_LOCALLY;
                detail = "渠道结算 " + channelNet + " 分，本地流水无记录";
            } else if (channelNet == 0L) {
                type = DiscrepancyType.MISSING_IN_CHANNEL;
                detail = "本地流水 " + localNet + " 分，渠道账单无记录";
            } else {
                type = DiscrepancyType.AMOUNT_MISMATCH;
                detail = "渠道净额 " + channelNet + " 分，本地净额 " + localNet + " 分";
            }
            return new ReconciliationDiscrepancy(
                    settlementDate, orderNo, type, channelNet, localNet, detail);
        }

        // Amounts agree, so the money itself is fine. The order's own state can
        // still contradict them: a ledger row written while the fold-up onto the
        // order failed leaves an order insisting it was never paid.
        PaymentOrder order = orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order != null && channelNet > 0L && UNSETTLED_STATUSES.contains(order.getStatus())) {
            return new ReconciliationDiscrepancy(
                    settlementDate, orderNo, DiscrepancyType.STATUS_MISMATCH, channelNet, localNet,
                    "双方金额一致（" + channelNet + " 分），但本地订单状态仍为 " + order.getStatus());
        }
        return null;
    }

    private Map<String, Long> readChannelBill(LocalDate settlementDate) {
        Path billPath = channel.billPath(settlementDate);
        if (!Files.exists(billPath)) {
            billPath = channel.writeDailyBill(settlementDate);
        }
        Map<String, Long> netByOrder = new LinkedHashMap<>();
        for (ChannelEntry entry : ChannelBillFile.read(billPath)) {
            long signed = entry.type() == ChannelEntryType.REFUND ? -entry.amountCents() : entry.amountCents();
            netByOrder.merge(entry.orderNo(), signed, Long::sum);
        }
        return netByOrder;
    }

    /**
     * Local rows are bucketed by when the channel says the money moved, not by
     * when we wrote them down — otherwise a callback that lands after midnight
     * falls into a different day than the bill line it is supposed to match.
     */
    private Map<String, Long> readLocalLedger(LocalDate settlementDate) {
        Instant from = settlementDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = settlementDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Long> netByOrder = new LinkedHashMap<>();
        for (PaymentTransaction transaction : transactionRepository.findByOccurredAtWindow(from, to)) {
            netByOrder.merge(transaction.getOrderNo(), transaction.signedCents(), Long::sum);
        }
        return netByOrder;
    }
}
