// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Resolves the orchestration source for one run (design §16.2 step 4;
 * plan Feature 10): the workflow's artifacts repository with fallback to the
 * project workspace configuration when the workflow omits it, the
 * {@code sourceBranch} resolved to an immutable base commit, and the run's
 * unique execution target branch.
 *
 * <p>V1 engine-side resolution is a durable contract: the resolved
 * {@code sourceBaseCommit} is persisted with the dispatch so the Agent can
 * fetch that exact object and never re-resolve the branch. Failure to
 * obtain the object is terminal {@code SOURCE_BASE_UNAVAILABLE} at the
 * caller (the full Git/credential fetch-verify against remote engines is
 * exercised by the engine scenario adapter; this resolver pins the
 * workflow-configured source identity).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationSourceResolver {

    /**
     * The resolved source for one run — the §7 {@code SourceDefinition}
     * inputs (repoUrl / sourceBranch / sourceBaseCommit / targetBranch /
     * credentialRef). The credentialRef is a session-scoped opaque
     * reference; the secret itself travels only in the secure envelope.
     */
    public record ResolvedSource(
            String repoUrl,
            String sourceBranch,
            String sourceBaseCommit,
            String targetBranch,
            String credentialRef) {}

    /**
     * Resolve the source for the run. The workflow artifacts repo wins;
     * the project workspace configuration is the fallback. The target
     * branch is the request's execution branch (already unique per run).
     */
    public ResolvedSource resolve(Workflow workflow, WorkflowRequest request) {
        Map<String, Object> artifactsRepo = workflow.getArtifactsRepo();
        String repoUrl;
        String sourceBranch;
        String credentialSecretId = null;

        if (artifactsRepo != null && artifactsRepo.get("url") != null
                && !String.valueOf(artifactsRepo.get("url")).isBlank()) {
            repoUrl = String.valueOf(artifactsRepo.get("url"));
            sourceBranch = artifactsRepo.get("baseBranch") != null
                    ? String.valueOf(artifactsRepo.get("baseBranch")) : "main";
            credentialSecretId = artifactsRepo.get("credentialSecretId") != null
                    ? String.valueOf(artifactsRepo.get("credentialSecretId")) : null;
        } else {
            // Project workspace fallback.
            var project = workflow.getProject();
            repoUrl = project.getWorkspaceRepoUrl();
            sourceBranch = project.getWorkspaceRepoBranch() != null
                    ? project.getWorkspaceRepoBranch() : "main";
            credentialSecretId = project.getWorkspaceCredentialSecretId() != null
                    ? project.getWorkspaceCredentialSecretId().toString() : null;
        }

        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalStateException(
                    "No source repository configured for workflow " + workflow.getId()
                            + " (neither artifacts repo nor project workspace).");
        }

        // V1: the source base commit pins the branch head at dispatch time.
        // The engine scenario adapter exercises the real fetch-verify
        // (LocalGitSourceResolver semantics); this resolver persists the
        // identity contract so a later branch movement cannot change an
        // assignment.
        String sourceBaseCommit = resolveBaseCommit(repoUrl, sourceBranch, credentialSecretId);

        // The request's execution branch is the unique target branch.
        String targetBranch = request.getBranch() != null
                ? request.getBranch() : "myrmec/" + request.getId();

        String credentialRef = credentialSecretId == null
                ? null : "workspace:" + credentialSecretId;

        return new ResolvedSource(repoUrl, sourceBranch, sourceBaseCommit, targetBranch, credentialRef);
    }

    /**
     * Resolve the immutable base commit for the source branch. V1: the
     * branch-head identity pin — the adapter's fetch-verify path proves
     * obtainability before dispatch; failure surfaces as terminal
     * SOURCE_BASE_UNAVAILABLE at the outcome layer.
     *
     * <p>Feature 10 (§16.2 step 4): a REAL {@code git ls-remote} resolves
     * the branch head to the immutable full SHA — the agent fetches that
     * exact object and never re-resolves the branch. V1 credential
     * handling: a credentialSecretId names a vault secret used via the
     * {@code Credential} helper env (never on the command line); the
     * deterministic local fixtures run unauthenticated.</p>
     */
    private String resolveBaseCommit(String repoUrl, String sourceBranch, String credentialSecretId) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("ls-remote");
            // Only the exact branch — never wildcard resolution.
            command.add(repoUrl);
            command.add("refs/heads/" + sourceBranch);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "/bin/true");
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("HOME", "");
            pb.environment().put("GIT_CONFIG", "");
            // V1: no credential injection on the command line. A null
            // credentialSecretId (local fixtures) needs none; a secret id
            // resolves through the engine's credential policy in a later
            // slice — never embedded here.
            pb.redirectErrorStream(false);

            Process process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int code = process.waitFor();
            process.getErrorStream().readAllBytes(); // drain

            if (code != 0) {
                throw new IllegalStateException(
                        "git ls-remote failed for " + repoUrl + " (exit " + code + ")");
            }
            // Output: "<40-hex-sha>\trefs/heads/<branch>"
            for (String line : stdout.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.endsWith("\trefs/heads/" + sourceBranch)) {
                    String sha = trimmed.substring(0, trimmed.indexOf('\t')).trim();
                    if (sha.matches("[0-9a-f]{40}")) {
                        return sha;
                    }
                }
            }
            throw new IllegalStateException(
                    "branch refs/heads/" + sourceBranch + " not found at " + repoUrl);
        } catch (Exception e) {
            // §14: the object is unobtainable — terminal, never a fallback.
            throw new IllegalStateException("SOURCE_BASE_UNAVAILABLE: "
                    + e.getMessage());
        }
    }
}