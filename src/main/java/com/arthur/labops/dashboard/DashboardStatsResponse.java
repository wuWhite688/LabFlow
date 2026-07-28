package com.arthur.labops.dashboard;

public record DashboardStatsResponse(
        long usersTotal,
        long equipmentTotal,
        long equipmentAvailable,
        long equipmentMaintenance,
        long reservationsPending,
        long reservationsApproved,
        long reservationsToday,
        long workOrdersOpen,
        long workOrdersUrgent
) {
}
