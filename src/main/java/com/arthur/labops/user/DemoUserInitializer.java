package com.arthur.labops.user;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@ConditionalOnProperty(name = "labops.demo-users.enabled", havingValue = "true", matchIfMissing = true)
public class DemoUserInitializer implements ApplicationRunner {

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoUserInitializer(PlatformUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfMissing("student", "student123", "林知夏", UserRole.STUDENT);
        createIfMissing("teacher", "teacher123", "周教授", UserRole.TEACHER);
        createIfMissing("technician", "tech123", "陈工", UserRole.TECHNICIAN);
        createIfMissing("technician2", "tech2123", "赵工", UserRole.TECHNICIAN);
        createIfMissing("admin", "admin123", "系统管理员", UserRole.ADMIN);
    }

    private void createIfMissing(String username, String password, String displayName, UserRole role) {
        if (!userRepository.existsByUsername(username)) {
            userRepository.save(new PlatformUser(username, passwordEncoder.encode(password), displayName, role));
        }
    }
}
