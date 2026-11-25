// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Junction row binding an {@link AgentProfileVersion} to a capability from the
 * catalog. Pure link table — no timestamp (see {@code 002-agents.xml}).
 */
@Entity
@Table(name = "agent_profile_capabilities")
@Getter
@Setter
@NoArgsConstructor
public class AgentProfileCapability {

    @EmbeddedId
    private AgentProfileCapabilityId id;
}
