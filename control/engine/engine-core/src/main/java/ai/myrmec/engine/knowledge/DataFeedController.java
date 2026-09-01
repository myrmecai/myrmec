// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.ActorType;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.CreateDataFeedRequest;
import ai.myrmec.engine.knowledge.dto.DataFeedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Data Feeds", description = "Content sync feeds into managed knowledge providers")
public class DataFeedController {

    private final DataFeedService service;

    @GetMapping("/api/v1/admin/data-feeds")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all org-scoped data feeds")
    public ResponseEntity<List<DataFeedResponse>> list() {
        return ResponseEntity.ok(service.findAllOrgScoped().stream()
                .map(DataFeedResponse::from).toList());
    }

    @GetMapping("/api/v1/admin/data-feeds/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get a data feed by id")
    public ResponseEntity<DataFeedResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(DataFeedResponse.from(service.findById(id)));
    }

    @PostMapping("/api/v1/admin/data-feeds")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a data feed")
    public ResponseEntity<DataFeedResponse> create(
            @Valid @RequestBody CreateDataFeedRequest request,
            @CurrentUser UUID userId) {
        var feed = service.create(request.scope(), request.projectId(), request.name(),
                request.description(), request.providerVersionId(), request.datasetName(),
                request.connectionConfigId(), request.connectionDetails(), request.syncSchedule(),
                userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.status(HttpStatus.CREATED).body(DataFeedResponse.from(feed));
    }

    @PostMapping("/api/v1/admin/data-feeds/{id}/sync")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Trigger a sync for a data feed")
    public ResponseEntity<DataFeedResponse> triggerSync(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(DataFeedResponse.from(
                service.triggerSync(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }

    @PostMapping("/api/v1/admin/data-feeds/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable a data feed")
    public ResponseEntity<DataFeedResponse> disable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(DataFeedResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }
}