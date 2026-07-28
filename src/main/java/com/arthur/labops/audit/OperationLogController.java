package com.arthur.labops.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.arthur.labops.common.PageResponse;

@RestController
@RequestMapping("/api/audit-logs")
public class OperationLogController {

    private final OperationLogQueryService queryService;

    public OperationLogController(OperationLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    PageResponse<OperationLogResponse> findAll(
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(queryService.findAll(actorUsername, action, targetType, pageable));
    }
}
