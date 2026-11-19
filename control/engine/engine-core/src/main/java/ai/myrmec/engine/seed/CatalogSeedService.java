// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import ai.myrmec.engine.agent.Capability;
import ai.myrmec.engine.agent.CapabilityMapping;
import ai.myrmec.engine.agent.CapabilityMappingId;
import ai.myrmec.engine.agent.CapabilityMappingRepository;
import ai.myrmec.engine.agent.CapabilityRepository;
import ai.myrmec.engine.agent.CapabilityTree;
import ai.myrmec.engine.agent.CapabilityTreeRepository;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.model.ModelCapability;
import ai.myrmec.engine.model.ModelCapabilityRepository;
import ai.myrmec.engine.setting.SettingType;
import ai.myrmec.engine.setting.SystemSetting;
import ai.myrmec.engine.setting.SystemSettingRepository;
import ai.myrmec.engine.user.AuthenticationProvider;
import ai.myrmec.engine.user.AuthenticationProviderRepository;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the {@code required/} reference data on system initialization. Every
 * method is idempotent (upsert keyed by the natural key) so a re-run after
 * clearing the initialization flag is safe.
 *
 * <p>Reads JSON packages from the classpath under {@code seed-data/required/}.
 * The data is inserted directly via repositories (not API endpoints) because
 * most required tables have no create endpoint and seeding happens during
 * startup, before the web layer serves traffic.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSeedService {

    /** System-setting key marking required seed completion (BOOL). */
    public static final String REQUIRED_SEED_COMPLETED_KEY = "system.required_seed_completed";

    private static final String BASE = "seed-data/required/";

    private final CapabilityRepository capabilityRepository;
    private final CapabilityTreeRepository capabilityTreeRepository;
    private final CapabilityMappingRepository capabilityMappingRepository;
    private final ModelCapabilityRepository modelCapabilityRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final GroupRepository groupRepository;
    private final AuthenticationProviderRepository authenticationProviderRepository;
    private final ObjectMapper objectMapper;

    /** True when the required tier has already been seeded. */
    @Transactional(readOnly = true)
    public boolean isRequiredSeedCompleted() {
        return systemSettingRepository.findById(REQUIRED_SEED_COMPLETED_KEY)
                .map(SystemSetting::getValue)
                .map("true"::equalsIgnoreCase)
                .orElse(false);
    }

    /**
     * Seed all required data in dependency order, then flip the completion
     * flag. Idempotent: safe to call more than once.
     */
    @Transactional
    public void seedRequired() {
        seedCapabilities();
        seedCapabilityTreeAndMappings();
        seedModelCapabilities();
        seedSystemSettings();
        seedOrgGroups();
        seedAuthenticationProviders();
        markRequiredSeedCompleted();
        log.info("Required seed data applied");
    }

    /** Capabilities catalog (capabilities table). */
    @Transactional
    public void seedCapabilities() {
        for (Capability seed : readList("agent-capabilities/capability-list.json", Capability.class)) {
            Capability existing = capabilityRepository.findById(seed.getCapabilityKey()).orElse(null);
            if (existing == null) {
                capabilityRepository.save(seed);
            } else {
                existing.setDisplayName(seed.getDisplayName());
                existing.setDescription(seed.getDescription());
                existing.setActive(seed.isActive());
                capabilityRepository.save(existing);
            }
        }
        log.info("Seeded capabilities catalog");
    }

    /**
     * Capability tree (capability_tree) plus its inline mappings
     * (capability_mappings). Tree nodes carry their capability keys; roots have
     * none.
     */
    @Transactional
    public void seedCapabilityTreeAndMappings() {
        List<CapabilityTreeSeed> nodes =
                readList("agent-capabilities/capability-tree.json", CapabilityTreeSeed.class);

        // Pass 1: upsert tree nodes (roots first, then children).
        nodes.stream()
                .filter(n -> n.parentId() == null)
                .forEach(this::upsertTree);
        nodes.stream()
                .filter(n -> n.parentId() != null)
                .forEach(this::upsertTree);

        // Pass 2: upsert capability mappings from each node's inline capabilities.
        for (CapabilityTreeSeed node : nodes) {
            if (node.capabilities() == null) {
                continue;
            }
            for (String capabilityKey : node.capabilities()) {
                CapabilityMappingId id = new CapabilityMappingId(capabilityKey, node.id());
                if (!capabilityMappingRepository.existsById(id)) {
                    CapabilityMapping mapping = new CapabilityMapping();
                    mapping.setId(id);
                    capabilityMappingRepository.save(mapping);
                }
            }
        }
        log.info("Seeded capability tree and mappings");
    }

    private void upsertTree(CapabilityTreeSeed seed) {
        CapabilityTree existing = capabilityTreeRepository.findById(seed.id()).orElse(null);
        if (existing == null) {
            CapabilityTree node = new CapabilityTree();
            node.setId(seed.id());
            node.setParentId(seed.parentId());
            node.setDisplayName(seed.displayName());
            node.setDescription(seed.description());
            node.setActive(seed.active());
            capabilityTreeRepository.save(node);
        } else {
            existing.setParentId(seed.parentId());
            existing.setDisplayName(seed.displayName());
            existing.setDescription(seed.description());
            existing.setActive(seed.active());
            capabilityTreeRepository.save(existing);
        }
    }

    /** Model capability catalog (model_capabilities table). */
    @Transactional
    public void seedModelCapabilities() {
        for (ModelCapability seed : readList("model-capabilities.json", ModelCapability.class)) {
            ModelCapability existing = modelCapabilityRepository.findById(seed.getCode()).orElse(null);
            if (existing == null) {
                modelCapabilityRepository.save(seed);
            } else {
                existing.setName(seed.getName());
                existing.setDescription(seed.getDescription());
                existing.setIsSystem(seed.getIsSystem());
                existing.setStatus(seed.getStatus());
                modelCapabilityRepository.save(existing);
            }
        }
        log.info("Seeded model capability catalog");
    }

    /** System settings (system_settings table). */
    @Transactional
    public void seedSystemSettings() {
        for (SystemSetting seed : readList("system-settings.json", SystemSetting.class)) {
            SystemSetting existing = systemSettingRepository.findById(seed.getKey()).orElse(null);
            if (existing == null) {
                systemSettingRepository.save(seed);
            } else {
                existing.setValueType(seed.getValueType());
                existing.setValue(seed.getValue());
                existing.setDescription(seed.getDescription());
                systemSettingRepository.save(existing);
            }
        }
        log.info("Seeded system settings");
    }

    /** Default organization group (org_groups table) with its fixed UUID. */
    @Transactional
    public void seedOrgGroups() {
        for (Group seed : readList("org-groups.json", Group.class)) {
            if (groupRepository.existsById(seed.getId())) {
                continue;
            }
            groupRepository.save(seed);
        }
        log.info("Seeded default organization group");
    }

    /** Authentication providers (authentication_providers table), all system rows. */
    @Transactional
    public void seedAuthenticationProviders() {
        for (AuthenticationProvider seed :
                readList("authentication-providers.json", AuthenticationProvider.class)) {
            AuthenticationProvider existing =
                    authenticationProviderRepository.findById(seed.getCode()).orElse(null);
            if (existing == null) {
                seed.setIsSystem(true);
                authenticationProviderRepository.save(seed);
            } else {
                existing.setProviderType(seed.getProviderType());
                existing.setName(seed.getName());
                existing.setIsEnabled(seed.getIsEnabled());
                existing.setIsSystem(true);
                existing.setMetadata(seed.getMetadata());
                authenticationProviderRepository.save(existing);
            }
        }
        log.info("Seeded authentication providers");
    }

    /** Flip the required-seed completion flag to true. */
    private void markRequiredSeedCompleted() {
        SystemSetting flag = systemSettingRepository.findById(REQUIRED_SEED_COMPLETED_KEY)
                .orElseGet(() -> {
                    SystemSetting s = new SystemSetting();
                    s.setKey(REQUIRED_SEED_COMPLETED_KEY);
                    s.setValueType(SettingType.BOOL);
                    return s;
                });
        flag.setValue("true");
        systemSettingRepository.save(flag);
    }

    /** Read a classpath JSON array of {@code type} into a list. */
    private <T> List<T> readList(String relativePath, Class<T> type) {
        ClassPathResource resource = new ClassPathResource(BASE + relativePath);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, type);
            return objectMapper.readValue(in, listType);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read required seed data: " + relativePath, e);
        }
    }
}
