package com.arthur.labops.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogQueryService {

    private final OperationLogRepository operationLogRepository;

    public OperationLogQueryService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<OperationLogResponse> findAll(String actorUsername, String action,
                                              String targetType, Pageable pageable) {
        Specification<OperationLog> specification = (root, query, builder) -> builder.conjunction();
        if (actorUsername != null && !actorUsername.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("actorUsername"), actorUsername.trim()));
        }
        if (action != null && !action.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("action"), action.trim()));
        }
        if (targetType != null && !targetType.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("targetType"), targetType.trim()));
        }
        return operationLogRepository.findAll(specification, pageable).map(OperationLogResponse::from);
    }
}
