// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unified session execution (design D10 part 2): the source resolver pins
 * each task's base commit to the run's target-branch head when that ref
 * exists (a predecessor's pushed output), else to the source-branch head
 * (the first task of a run). The pinning is a REAL {@code git ls-remote}
 * against a seeded local bare origin.
 */
@DisplayName("Unified execution: OrchestrationSourceResolver target-branch retarget (D10)")
class OrchestrationSourceResolverTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private OrchestrationSourceResolver resolver;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRequestRepository requestRepository;

    private Project project;
    private User admin;
    private String originUrl;

    @BeforeEach
    void setUp() throws Exception {
        Path origin = Files.createTempDirectory("resolver-origin-");
        origin = origin.resolve("origin.git");
        git(origin.getParent(), "init", "--bare", "-b", "main", origin.toString());
        Path seed = Files.createTempDirectory("resolver-seed-");
        git(seed, "init", "-b", "main");
        git(seed, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "seed");
        originUrl = origin.toAbsolutePath().toString().replace('\\', '/');
        git(seed, "push", originUrl, "main");
        seed.toFile().deleteOnExit();

        project = data.project().named("resolver").withRepo(originUrl, "main").create();
        admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
    }

    @Test
    @DisplayName("no target ref yet (first task): the source-branch head is pinned")
    void firstTaskPinsSourceBranchHead() throws Exception {
        // Push the predecessor output AFTER the workflow/request rows are
        // created but with a DIFFERENT target branch than this request uses.
        // Simpler: this request uses its own unique branch - nothing pushed
        // to it - so the source-branch head wins.
        Workflow workflow = workflowWithArtifactsRepo("resolver-first");
        WorkflowRequest request = requestOf(workflow, "resolver/first-task-branch");

        var source = resolver.resolve(workflow, request);

        assertThat(source.sourceBranch()).isEqualTo("main");
        String sourceHead = lsRemoteHead(originUrl, "refs/heads/main");
        assertThat(source.sourceBaseCommit())
                .as("the first task of a run falls back to the source-branch head")
                .isEqualTo(sourceHead);
        assertThat(source.targetBranch()).isEqualTo("resolver/first-task-branch");
    }

    @Test
    @DisplayName("target-branch head exists (a predecessor pushed): that head is pinned")
    void predecessorOutputPinsTargetBranchHead() throws Exception {
        Workflow workflow = workflowWithArtifactsRepo("resolver-retarget");
        WorkflowRequest request = requestOf(workflow, null);
        String targetBranch = "myrmec/" + request.getId();

        // A predecessor attempt pushed its commit to the run's target branch.
        Path work = Files.createTempDirectory("resolver-work-");
        git(work, "clone", originUrl, work.resolve("wt").toString());
        Path wt = work.resolve("wt");
        git(wt, "-c", "user.email=t@t", "-c", "user.name=t",
                "commit", "--allow-empty", "-m", "predecessor output");
        git(wt, "push", originUrl, "HEAD:refs/heads/" + targetBranch);
        work.toFile().deleteOnExit();

        String targetHead = lsRemoteHead(originUrl, "refs/heads/" + targetBranch);

        var source = resolver.resolve(workflow, request);

        assertThat(source.targetBranch()).isEqualTo(targetBranch);
        assertThat(source.sourceBaseCommit())
                .as("the pushed predecessor output is the pinned base commit")
                .isEqualTo(targetHead);
        // Deterministic: the pin IS the ref head, never a re-resolution.
        assertThat(lsRemoteHead(originUrl, "refs/heads/" + targetBranch))
                .isEqualTo(targetHead);
    }

    @Test
    @DisplayName("an unresolvable source repo is terminal SOURCE_BASE_UNAVAILABLE")
    void unresolvedSourceIsTerminalFailure() throws Exception {
        Workflow workflow = workflowWithArtifactsRepo("resolver-dead");
        // The workflow's artifacts repo points at a temp dir with no
        // repository - the ls-remote cannot resolve anything.
        Path empty = Files.createTempDirectory("resolver-empty-");
        workflow.setArtifactsRepo(Map.of(
                "url", empty.toAbsolutePath().toString().replace('\\', '/'),
                "baseBranch", "main"));
        workflowRepository.save(workflow);
        WorkflowRequest request = requestOf(workflow, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> resolver.resolve(workflow, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOURCE_BASE_UNAVAILABLE");
        empty.toFile().deleteOnExit();
    }

    // ---------------- fixtures ----------------

    private Workflow workflowWithArtifactsRepo(String tag) {
        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName(tag + "-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        wf.setArtifactsRepo(Map.of(
                "url", originUrl,
                "baseBranch", "main"));
        return workflowRepository.save(wf);
    }

    private WorkflowRequest requestOf(Workflow workflow, String branch) {
        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(ai.myrmec.engine.workflow.RequestStatus.RUNNING);
        req.setBranch(branch);
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        return requestRepository.save(req);
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.Arrays.asList(args));
        Process p = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + args[0] + " failed: "
                    + new String(p.getInputStream().readAllBytes()));
        }
    }

    /** The full SHA the real git ls-remote reports for one ref. */
    private static String lsRemoteHead(String repoUrl, String refPath) throws Exception {
        Process p = new ProcessBuilder("git", "ls-remote", repoUrl, refPath)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        for (String line : out.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.endsWith("\t" + refPath)) {
                return trimmed.substring(0, trimmed.indexOf('\t')).trim();
            }
        }
        throw new IllegalStateException("ref not found: " + refPath + " at " + repoUrl);
    }
}