package com.arthur.labops.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserDirectoryController {

    private final PlatformUserRepository userRepository;

    public UserDirectoryController(PlatformUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/technicians")
    List<TechnicianResponse> technicians() {
        return userRepository.findByRoleAndEnabledTrueOrderByDisplayName(UserRole.TECHNICIAN)
                .stream()
                .map(TechnicianResponse::from)
                .toList();
    }
}
