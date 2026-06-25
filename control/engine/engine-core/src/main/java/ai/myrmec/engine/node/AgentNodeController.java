package ai.myrmec.engine.node;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent-facing node directory (slice 4b, agent-concurrency §9.8). An Agent Host
 * resolves the in-cluster address of the replica that owns a worker's
 * conversation socket ({@code agents.home_node_id}) so it can open that
 * conversation socket directly against the owning pod rather than the VIP.
 *
 * <p>Secured by the {@code /api/v1/agent/**} chain (AGENT role).</p>
 */
@RestController
@RequestMapping("/api/v1/agent/nodes")
@RequiredArgsConstructor
public class AgentNodeController {

    private final EngineNodeRepository nodeRepository;

    @GetMapping("/{nodeId}")
    public NodeAddressResponse resolve(@PathVariable String nodeId) {
        return nodeRepository.findById(nodeId)
                .map(node -> new NodeAddressResponse(node.getNodeId(), node.getAddress(), node.getStatus().name()))
                .orElseThrow(() -> ResourceNotFoundException.of("EngineNode", nodeId));
    }
}
