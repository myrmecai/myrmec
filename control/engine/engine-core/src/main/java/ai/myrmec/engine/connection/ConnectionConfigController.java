// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.connection.dto.ConnectionConfigResponse;
import ai.myrmec.engine.connection.dto.ConnectionConfigVersionResponse;
import ai.myrmec.engine.connection.dto.CreateConnectionConfigRequest;
import ai.myrmec.engine.connection.dto.UpdateDraftRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Connection Config Controller — admin endpoints for managing global
 * connection definitions with the versioned entity pattern.
 *
 * <p>See UC-018 for the full state machine and API design.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Connection Configs", description = "Global connection definitions (GIT, HTTP, S3, DB_SCHEMA, MANAGED_RAG)")
public class ConnectionConfigController {

    private final ConnectionConfigService service;

    // ---- List / Get --------------------------------------------------

    @GetMapping("/api/v1/admin/connection-configs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all org-scoped connection configs")
    public ResponseEntity<List<ConnectionConfigResponse>> listOrgScoped() {
        return ResponseEntity.ok(service.findAllOrgScoped().stream()
                .map(ConnectionConfigResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/admin/connection-configs/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get a connection config by id")
    public ResponseEntity<ConnectionConfigResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ConnectionConfigResponse.from(service.findById(id)));
    }

    @GetMapping("/api/v1/admin/connection-configs/{id}/published-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the published version of a connection config")
    public ResponseEntity<ConnectionConfigVersionResponse> getPublishedVersion(@PathVariable UUID id) {
        return ResponseEntity.ok(ConnectionConfigVersionResponse.from(service.getPublishedVersion(id)));
    }

    @GetMapping("/api/v1/admin/connection-configs/{id}/draft-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the draft version of a connection config (if any)")
    public ResponseEntity<ConnectionConfigVersionResponse> getDraftVersion(@PathVariable UUID id) {
        var draft = service.getDraftVersion(id);
        if (draft == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(ConnectionConfigVersionResponse.from(draft));
    }

    // ---- Create ------------------------------------------------------

    @PostMapping("/api/v1/admin/connection-configs")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a new connection config")
    public ResponseEntity<ConnectionConfigResponse> create(
            @Valid @RequestBody CreateConnectionConfigRequest request,
            @CurrentUser UUID userId) {
        var config = service.create(
                request.scope(),
                request.projectId(),
                request.name(),
                request.description(),
                request.type(),
                request.credentialSecretId(),
                userId,
                userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionConfigResponse.from(config));
    }

    // ---- Version management ------------------------------------------

    @PostMapping("/api/v1/admin/connection-configs/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a new draft version")
    public ResponseEntity<ConnectionConfigVersionResponse> createDraft(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        var draft = service.createDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConnectionConfigVersionResponse.from(draft));
    }

    @PutMapping("/api/v1/admin/connection-configs/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update the draft version (url, config)")
    public ResponseEntity<ConnectionConfigVersionResponse> updateDraft(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDraftRequest request,
            @CurrentUser UUID userId) {
        var draft = service.updateDraft(id, request.url(), request.config(), userId);
        return ResponseEntity.ok(ConnectionConfigVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/connection-configs/{id}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Publish the current draft version")
    public ResponseEntity<ConnectionConfigVersionResponse> publish(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        var published = service.publishDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.ok(ConnectionConfigVersionResponse.from(published));
    }

    @DeleteMapping("/api/v1/admin/connection-configs/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Discard the current draft version")
    public ResponseEntity<Void> discardDraft(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        service.discardDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.noContent().build();
    }

    // ---- Lifecycle ---------------------------------------------------

    @PostMapping("/api/v1/admin/connection-configs/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable a connection config")
    public ResponseEntity<ConnectionConfigResponse> disable(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(ConnectionConfigResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/connection-configs/{id}/reenable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Re-enable a connection config")
    public ResponseEntity<ConnectionConfigResponse> reenable(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(ConnectionConfigResponse.from(
                service.reenable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/connection-configs/{id}/archive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Archive a connection config")
    public ResponseEntity<ConnectionConfigResponse> archive(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(ConnectionConfigResponse.from(
                service.archive(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }
}