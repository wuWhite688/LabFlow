package com.arthur.labops.audit;

import java.time.Instant;

public record OperationLogResponse(
        Long id,
        Long actorUserId,
        String actorUsername,
        String actorRole,
        String action,
        String targetType,
        Long targetId,
        String details,
        Instant createdAt
) {
    static OperationLogResponse from(OperationLog log) {
        return new OperationLogResponse(
                log.getId(), log.getActorUserId(), log.getActorUsername(), log.getActorRole(),
                log.getAction(), log.getTargetType(), log.getTargetId(), log.getDetails(), log.getCreatedAt());
    }
}
