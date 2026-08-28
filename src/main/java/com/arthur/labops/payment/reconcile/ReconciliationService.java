package com.arthur.labops.payment.reconcile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.channel.ChannelBillFile;
import com.arthur.labops.payment.channel.ChannelEntry;
import com.arthur.labops.payment.channel.ChannelEntryType;
import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

/**
 * Compares the channel's T+1 settlement file against the local books, order by
 * order, and turns every disagreement into a ticket.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SimulatedPaymentChannel channel;
    private final PaymentOrderRepository orderRepository;
    private final PaymentDiscrepancyTicketService ticketService;

    public ReconciliationService(SimulatedPaymentChannel channel,
                                 PaymentOrderRepository orderRepository,
                                 PaymentDiscrepancyTicketService ticketService) {
        this.channel = channel;
        this.orderRepository = orderRepository;
        this.ticketService = ticketService;
    }

    /**
     * Not transactional as a whole: each ticket gets its own transaction so one
     * unmappable order cannot roll back the findings already recorded.
     */
    public ReconciliationReport reconcile(LocalDate settlementDate) {
        Path billPath = channel.billPath(settlementDate);
        if (!Files.exists(billPath)) {
            billPath = channel.writeDailyBill(settlementDate);
        }
        List<ChannelEntry> billEntries = ChannelBillFile.read(billPath);

        Map<String, Long> channelNetByOrder = new LinkedHashMap<>();
        for (ChannelEntry entry : billEntries) {
            long signed = entry.type() == ChannelEntryType.REFUND ? -entry.amountCents() : entry.amountCents();
            channelNetByOrder.merge(entry.orderNo(), signed, Long::sum);
        }

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        List<PaymentOrder> orders = orderRepository.findAllByOrderNoIn(List.copyOf(channelNetByOrder.keySet()));
        for (PaymentOrder order : orders) {
            long channelNet = channelNetByOrder.get(order.getOrderNo());
            long expected = order.getAmountCents();
            if (channelNet != expected) {
                discrepancies.add(new ReconciliationDiscrepancy(
                        settlementDate,
                        order.getOrderNo(),
                        DiscrepancyType.AMOUNT_MISMATCH,
                        channelNet,
                        expected,
                        "渠道净额 " + channelNet + " 分，本地应收 " + expected + " 分"));
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
                settlementDate, orders.size(), discrepancies.size(), tickets);
        return new ReconciliationReport(settlementDate, orders.size(), List.copyOf(discrepancies), tickets);
    }
}
