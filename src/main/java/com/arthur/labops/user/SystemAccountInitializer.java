package com.arthur.labops.user;

import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform's own account, used as the reporter on records the platform
 * raises for itself (currently reconciliation discrepancy tickets).
 *
 * <p>{@code fault_work_orders.reporter_id} has a foreign key to this table, so a
 * system-raised ticket needs a real row rather than a magic id. The account is
 * created disabled and its password hash is a fresh random value never written
 * down anywhere, so it cannot be logged into — it exists to be pointed at.
 */
@Component
public class SystemAccountInitializer implements ApplicationRunner {

    public static final String SYSTEM_USERNAME = "system";

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SystemAccountInitializer(PlatformUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(SYSTEM_USERNAME)) {
            return;
        }
        PlatformUser system = new PlatformUser(
                SYSTEM_USERNAME,
                passwordEncoder.encode(UUID.randomUUID().toString()),
                "系统",
                UserRole.ADMIN);
        system.setEnabled(false);
        userRepository.save(system);
    }
}
