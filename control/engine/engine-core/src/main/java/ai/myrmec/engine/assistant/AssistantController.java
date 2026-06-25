package ai.myrmec.engine.assistant;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.assistant.dto.AddGrantRequest;
import ai.myrmec.engine.assistant.dto.AssistantGrantResponse;
import ai.myrmec.engine.assistant.dto.AssistantResponse;
import ai.myrmec.engine.assistant.dto.AssistantVersionResponse;
import ai.myrmec.engine.assistant.dto.CreateAssistantRequest;
import ai.myrmec.engine.assistant.dto.SetDisabledRequest;
import ai.myrmec.engine.assistant.dto.UpdateAssistantRequest;
import ai.myrmec.engine.assistant.dto.UpdateDraftRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User-facing REST surface for the Assistant entity (#92, UC-017).
 *
 * <p>Parent-row CRUD + kill switches, the Draft/Publish version lifecycle, and
 * the {@code assistant_grants} ACL. Authorization is project-scoped via
 * {@code assistantAccess} (VIEWER reads, EDITOR authors, OWNER manages grants);
 * per-assistant grant enforcement is a follow-up (#99).</p>
 */
@RestController
@RequestMapping("/api/v1/assistants")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Assistants", description = "Assistant authoring and versioning (#92)")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantVersionService versionService;
    private final ai.myrmec.engine._system.security.AssistantAccessEvaluator assistantAccess;

    // ==================== Parent ====================

    @PostMapping
    @Operation(summary = "Create an assistant (start dialog) with an initial draft")
    @PreAuthorize("@projectAccess.canEdit(#request.projectId, authentication) "
            + "and @projectAccess.allowsServiceType(#request.projectId, 'CONVERSATIONAL')")
    public ResponseEntity<AssistantResponse> create(
            @Valid @RequestBody CreateAssistantRequest request,
            @CurrentUser UUID userId) {
        Assistant a = assistantService.createAssistant(
                request.projectId(), request.name(), request.description(),
                request.agentProfileId(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AssistantResponse.from(a));
    }

    @GetMapping
    @Operation(summary = "List assistants under a project (by name)")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ResponseEntity<List<AssistantResponse>> listByProject(@RequestParam UUID projectId) {
        List<AssistantResponse> body = assistantService.listByProject(projectId).stream()
                .map(AssistantResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an assistant by id")
    @PreAuthorize("@assistantAccess.canView(#id, authentication)")
    public ResponseEntity<AssistantResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(AssistantResponse.from(assistantService.getAssistant(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit parent-row fields (name/description) in place, no version bump")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAssistantRequest request) {
        Assistant a = assistantService.updateParent(id, request.name(), request.description());
        return ResponseEntity.ok(AssistantResponse.from(a));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Toggle the reversible disabled kill switch")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantResponse> setDisabled(
            @PathVariable UUID id,
            @Valid @RequestBody SetDisabledRequest request) {
        Assistant a = assistantService.setDisabled(id, request.disabled());
        return ResponseEntity.ok(AssistantResponse.from(a));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Soft-delete (archive) an assistant; reversible, no hard delete")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantResponse> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(AssistantResponse.from(assistantService.archive(id)));
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "Restore an archived assistant")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantResponse> unarchive(@PathVariable UUID id) {
        return ResponseEntity.ok(AssistantResponse.from(assistantService.unarchive(id)));
    }

    // ==================== Versions ====================

    @GetMapping("/{id}/versions")
    @Operation(summary = "List an assistant's versions (newest first)")
    @PreAuthorize("@assistantAccess.canView(#id, authentication)")
    public ResponseEntity<List<AssistantVersionResponse>> listVersions(@PathVariable UUID id) {
        List<AssistantVersionResponse> body = versionService.listVersions(id).stream()
                .map(AssistantVersionResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/draft")
    @Operation(summary = "Get the single open draft")
    @PreAuthorize("@assistantAccess.canView(#id, authentication)")
    public ResponseEntity<AssistantVersionResponse> getDraft(@PathVariable UUID id) {
        return ResponseEntity.ok(AssistantVersionResponse.from(versionService.getOpenDraft(id)));
    }

    @PostMapping("/{id}/versions")
    @Operation(summary = "Open a new draft by cloning the current published version (409 if a draft exists)")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantVersionResponse> openDraft(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        AssistantVersion draft = versionService.openDraft(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AssistantVersionResponse.from(draft));
    }

    @PatchMapping("/{id}/draft")
    @Operation(summary = "Edit the open draft's behaviour fields")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantVersionResponse> updateDraft(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDraftRequest request) {
        AssistantVersion draft = versionService.getOpenDraft(id);
        applyDraftEdits(draft, request);
        AssistantVersion saved = versionService.saveDraft(draft);
        return ResponseEntity.ok(AssistantVersionResponse.from(saved));
    }

    @DeleteMapping("/{id}/draft")
    @Operation(summary = "Discard the open draft, freeing the single-draft slot")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<Void> discardDraft(@PathVariable UUID id) {
        versionService.discardDraft(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/draft/takeover")
    @Operation(summary = "Reassign the open draft to the current user (owners may take over anyone's)")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantVersionResponse> takeOverDraft(
            @PathVariable UUID id,
            @CurrentUser UUID userId,
            Authentication authentication) {
        boolean allowAnyOwner = assistantAccess.canOwn(id, authentication);
        AssistantVersion draft = versionService.takeOverDraft(id, userId, allowAnyOwner);
        return ResponseEntity.ok(AssistantVersionResponse.from(draft));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish the open draft (runs the full publish gate)")
    @PreAuthorize("@assistantAccess.canEdit(#id, authentication)")
    public ResponseEntity<AssistantVersionResponse> publish(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        AssistantVersion published = versionService.publish(id, userId);
        return ResponseEntity.ok(AssistantVersionResponse.from(published));
    }

    // ==================== Grants ====================

    @GetMapping("/{id}/grants")
    @Operation(summary = "List an assistant's ACL grants")
    @PreAuthorize("@assistantAccess.canOwn(#id, authentication)")
    public ResponseEntity<List<AssistantGrantResponse>> listGrants(@PathVariable UUID id) {
        List<AssistantGrantResponse> body = assistantService.listGrants(id).stream()
                .map(AssistantGrantResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/grants")
    @Operation(summary = "Add an ACL grant")
    @PreAuthorize("@assistantAccess.canOwn(#id, authentication)")
    public ResponseEntity<AssistantGrantResponse> addGrant(
            @PathVariable UUID id,
            @Valid @RequestBody AddGrantRequest request,
            @CurrentUser UUID userId) {
        AssistantGrant grant = assistantService.addGrant(
                id, request.principalType(), request.principalId(), request.permission(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(AssistantGrantResponse.from(grant));
    }

    @DeleteMapping("/{id}/grants/{grantId}")
    @Operation(summary = "Remove an ACL grant")
    @PreAuthorize("@assistantAccess.canOwn(#id, authentication)")
    public ResponseEntity<Void> removeGrant(@PathVariable UUID id, @PathVariable UUID grantId) {
        assistantService.removeGrant(id, grantId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Internals ====================

    /** Apply non-null Zone-2 fields from the request onto the draft entity. */
    private void applyDraftEdits(AssistantVersion draft, UpdateDraftRequest req) {
        if (req.agentProfileId() != null) {
            draft.setAgentProfileId(req.agentProfileId());
        }
        if (req.addendum() != null) {
            draft.setAddendum(req.addendum());
        }
        if (req.greetingMessage() != null) {
            draft.setGreetingMessage(req.greetingMessage());
        }
        if (req.maxIdleMinutes() != null) {
            draft.setMaxIdleMinutes(req.maxIdleMinutes());
        }
        if (req.maxSessionAgeHours() != null) {
            draft.setMaxSessionAgeHours(req.maxSessionAgeHours());
        }
        if (req.kbBindings() != null) {
            draft.setKbBindings(req.kbBindings());
        }
        if (req.disabledTools() != null) {
            draft.setDisabledTools(req.disabledTools());
        }
        if (req.hitlOverrideMode() != null) {
            draft.setHitlOverrideMode(
                    AssistantVersion.HitlOverrideMode.valueOf(req.hitlOverrideMode()));
        }
        if (req.usableVia() != null) {
            draft.setUsableVia(req.usableVia());
        }
        if (req.attachmentsEnabled() != null) {
            draft.setAttachmentsEnabled(req.attachmentsEnabled());
        }
        if (req.attachmentRetentionTtl() != null) {
            draft.setAttachmentRetentionTtl(req.attachmentRetentionTtl());
        }
        if (req.attachmentMaxFileSize() != null) {
            draft.setAttachmentMaxFileSize(req.attachmentMaxFileSize());
        }
        if (req.attachmentTypeAllowlist() != null) {
            draft.setAttachmentTypeAllowlist(req.attachmentTypeAllowlist());
        }
    }
}
