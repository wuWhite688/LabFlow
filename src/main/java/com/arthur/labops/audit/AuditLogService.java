package com.arthur.labops.audit;

import org.springframework.stereotype.Service;

import com.arthur.labops.user.PlatformUser;

@Service
public class AuditLogService {

    private final OperationLogRepository operationLogRepository;

    public AuditLogService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    public void record(PlatformUser actor, String action, String targetType, Long targetId, String details) {
        operationLogRepository.save(new OperationLog(actor, action, targetType, targetId, details));
    }

    public void recordSystem(String action, String targetType, Long targetId, String details) {
        operationLogRepository.save(new OperationLog(action, targetType, targetId, details));
    }
}
