package com.arthur.labops.payment.reconcile;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.arthur.labops.payment.channel.SimulatedPaymentChannel;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final SimulatedPaymentChannel channel;
    private final ReconciliationService reconciliationService;

    public ReconciliationController(SimulatedPaymentChannel channel,
                                    ReconciliationService reconciliationService) {
        this.channel = channel;
        this.reconciliationService = reconciliationService;
    }

    /** Admin-triggered re-run. Safe to repeat: tickets are keyed by the discrepancy. */
    @PostMapping("/run")
    ReconciliationReport run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate) {
        channel.writeDailyBill(settlementDate);
        return reconciliationService.reconcile(settlementDate);
    }
}
