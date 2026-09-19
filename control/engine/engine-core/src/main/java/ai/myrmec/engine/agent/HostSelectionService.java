// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Capacity-based host selection (decoupling §3.7): the engine picks a host
 * because it is ACTIVE, project-visible, and has a live OPEN instance —
 * never because of a profile binding. Profiles bind at session time
 * (conversation assistant pin / workflow task), not at the host.
 *
 * <p>Preference order: a host scoped to {@code projectId} first, then a
 * system-wide (unscoped) host. Hosts with no live OPEN instance are
 * excluded — capacity is real, not registered.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HostSelectionService {

    private final AgentHostRepository agentHostRepository;
    private final AgentHostInstanceRepository instanceRepository;

    /**
     * Return all active hosts able to serve {@code projectId} in
     * selection order: project-scoped hosts with live OPEN instances first,
     * then unscoped live hosts. Empty when no candidate exists.
     */
    @Transactional(readOnly = true)
    public List<AgentHost> selectCandidatesForProject(UUID projectId) {
        List<AgentHost> active = agentHostRepository.findByStatus(AgentHost.Status.ACTIVE);
        return active.stream()
                .filter(this::hasLiveInstance)
                .filter(h -> projectId == null || h.getProjectId() == null
                        || projectId.equals(h.getProjectId()))
                .sorted(java.util.Comparator.comparingInt((AgentHost h) -> score(h, projectId)))
                .toList();
    }

    /**
     * Pick one active host able to serve {@code projectId}: live OPEN
     * instance required, project-scoped hosts preferred, then unscoped.
     * Empty when no candidate exists (callers treat as "no host online").
     */
    @Transactional(readOnly = true)
    public Optional<AgentHost> selectForProject(UUID projectId) {
        return selectCandidatesForProject(projectId).stream().findFirst();
    }

    /**
     * Project-scoped beats unscoped when a project is given; when no project
     * is given, system-wide (unscoped) hosts are preferred over project-scoped
     * ones (design §3.7).
     */
    private int score(AgentHost host, UUID projectId) {
        if (projectId == null) {
            return host.getProjectId() == null ? 0 : 1;
        }
        return projectId.equals(host.getProjectId()) ? 0 : 1;
    }

    private boolean hasLiveInstance(AgentHost host) {
        return !instanceRepository
                .findByAgentHostIdAndStatus(host.getId(), AgentHostInstance.Status.OPEN)
                .isEmpty();
    }
}
