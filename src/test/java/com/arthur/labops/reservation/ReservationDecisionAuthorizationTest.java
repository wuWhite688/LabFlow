package com.arthur.labops.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.UserRole;

class ReservationDecisionAuthorizationTest {

    @Test
    void rejectsStudentBeforeReadingOrLockingReservationData() {
        CurrentUserService currentUserService = new CurrentUserService(null) {
            @Override
            public PlatformUser getRequiredUser() {
                return new PlatformUser("student", "unused", "Student", UserRole.STUDENT);
            }
        };
        ReservationService service = new ReservationService(
                rejectAccess(EquipmentRepository.class), rejectAccess(ReservationRepository.class),
                null, null, Duration.ofMinutes(15), Duration.ofHours(12), Duration.ofDays(30),
                20, Duration.ofMinutes(10), null, null, currentUserService, null, null);

        assertThatThrownBy(() -> service.decide(
                1L, new ReservationDecisionRequest(ReservationStatus.APPROVED)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode())
                            .isEqualTo("RESERVATION_DECISION_FORBIDDEN");
                    assertThat(exception.getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    private static <T> T rejectAccess(Class<T> repositoryType) {
        return repositoryType.cast(Proxy.newProxyInstance(
                repositoryType.getClassLoader(), new Class<?>[] {repositoryType},
                (proxy, method, args) -> {
                    throw new AssertionError("Unauthorized request accessed repository: " + method.getName());
                }));
    }
}
