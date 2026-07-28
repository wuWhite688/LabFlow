package com.arthur.labops.reservation.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;

/**
 * End-to-end proof against a real RabbitMQ broker (typically the docker-compose
 * {@code labflow-rabbitmq} already used for production verification).
 *
 * <p>Schedule a long delay first, then a short delay; the short reservation must be
 * expired by the Rabbit path while the long one is still PENDING. Under the old
 * shared-FIFO + per-message TTL design the short message would be blocked.
 *
 * <p>Skipped automatically when {@code 127.0.0.1:5672} is unreachable so pure offline
 * {@code mvn test} stays green.
 */
@SpringBootTest(properties = {
        "labops.reservation-expiry.mode=rabbit",
        "labops.reservation-lock.mode=local",
        // Compensation must not steal the short-expiry race during this test.
        "labops.reservation-expiry.scan-interval=3600000",
        "labops.reservation-approval-timeout=1h",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true",
        // Isolated topology so a running production backend cannot steal test messages.
        "labops.reservation-expiry.rabbit.expiry-exchange=labops.reservation.expiry.exchange.test",
        "labops.reservation-expiry.rabbit.expiry-queue=labops.reservation.expiry.queue.test",
        "labops.reservation-expiry.rabbit.expiry-routing-key=reservation.expire.test",
        "labops.reservation-expiry.rabbit.delay-queue-prefix=labops.reservation.expiry.delay.test.",
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false"
})
class RabbitReservationExpiryOrderingIntegrationTest {

    @BeforeAll
    static void requireLocalRabbit() {
        assumeTrue(isPortOpen("127.0.0.1", 5672),
                "Skip HOL Rabbit IT: no broker on 127.0.0.1:5672 (start docker-compose middleware to enable)");
    }

    @DynamicPropertySource
    static void rabbitProps(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> 5672);
        registry.add("spring.rabbitmq.username",
                () -> firstNonBlank(System.getenv("RABBITMQ_USERNAME"), "labops"));
        registry.add("spring.rabbitmq.password",
                () -> firstNonBlank(System.getenv("RABBITMQ_PASSWORD"), "labflow_rabbit_verify_only"));
    }

    @Autowired
    private RabbitReservationExpiryScheduler scheduler;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void shortDelayIsProcessedBeforeLongDelayEvenWhenLongWasScheduledFirst() throws Exception {
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();

        Instant now = Instant.now();
        // DB expiresAt slightly earlier than broker fire time so expireIfPending accepts broker TTL jitter.
        Instant longExpiresAt = now.plusSeconds(20);
        Instant shortExpiresAt = now.plusSeconds(1);
        Instant longScheduleAt = now.plusSeconds(25);
        Instant shortScheduleAt = now.plusSeconds(2);

        // DB expiresAt must match scheduled fire time (expireIfPending checks expiresAt <= now).
        Reservation longPending = createPending(student, "LONG", longExpiresAt);
        Reservation shortPending = createPending(student, "SHORT", shortExpiresAt);

        // Long first — under the old shared FIFO design this would block the short message.
        scheduler.schedule(longPending.getId(), longScheduleAt);
        scheduler.schedule(shortPending.getId(), shortScheduleAt);

        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < deadline) {
            Reservation shortRow = reservationRepository.findById(shortPending.getId()).orElseThrow();
            Reservation longRow = reservationRepository.findById(longPending.getId()).orElseThrow();
            if (shortRow.getStatus() == ReservationStatus.EXPIRED
                    && longRow.getStatus() == ReservationStatus.PENDING) {
                break;
            }
            Thread.sleep(150);
        }

        Reservation shortAfter = reservationRepository.findById(shortPending.getId()).orElseThrow();
        Reservation longAfter = reservationRepository.findById(longPending.getId()).orElseThrow();

        assertThat(shortAfter.getStatus())
                .as("short-delay reservation must expire first via Rabbit DLX path")
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(longAfter.getStatus())
                .as("long-delay reservation must still be PENDING (proves no HOL wait on 25s message)")
                .isEqualTo(ReservationStatus.PENDING);
    }

    private Reservation createPending(PlatformUser student, String codeSuffix, Instant expiresAt) {
        Equipment equipment = equipmentRepository.save(new Equipment(
                "HOL-" + codeSuffix + "-" + System.nanoTime(),
                "HOL test " + codeSuffix,
                "test",
                "lab"));

        Instant start = Instant.now().plus(Duration.ofDays(3));
        Instant end = start.plus(Duration.ofHours(1));
        Reservation reservation = new Reservation(
                equipment,
                student.getId(),
                student.getDisplayName(),
                "HOL ordering " + codeSuffix,
                start,
                end,
                expiresAt);
        return reservationRepository.save(reservation);
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
