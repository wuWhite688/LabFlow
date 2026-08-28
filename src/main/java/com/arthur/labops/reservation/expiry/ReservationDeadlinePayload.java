package com.arthur.labops.reservation.expiry;

/**
 * Wire format for a delay message.
 *
 * <p>Approval deadlines keep the original bare-numeric payload so messages
 * already sitting in a broker when this version deploys still parse. Payment
 * deadlines are tagged.
 */
public final class ReservationDeadlinePayload {

    private ReservationDeadlinePayload() {
    }

    public static String encode(ReservationDeadlineKind kind, Long reservationId) {
        return kind == ReservationDeadlineKind.APPROVAL
                ? reservationId.toString()
                : kind.name() + ":" + reservationId;
    }

    /** @return the decoded deadline, or null when the payload is unusable */
    public static Decoded decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String trimmed = payload.trim();
        int separator = trimmed.indexOf(':');
        ReservationDeadlineKind kind = ReservationDeadlineKind.APPROVAL;
        String idPart = trimmed;
        if (separator >= 0) {
            try {
                kind = ReservationDeadlineKind.valueOf(trimmed.substring(0, separator));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            idPart = trimmed.substring(separator + 1);
        }
        try {
            return new Decoded(kind, Long.valueOf(idPart));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Decoded(ReservationDeadlineKind kind, Long reservationId) {
    }
}
