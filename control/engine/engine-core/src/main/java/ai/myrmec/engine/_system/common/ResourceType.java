// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.common;

/**
 * Resource type identifiers — the {@code entity_type} column in
 * {@code audit_events}. Identifies <em>what kind of entity</em> an
 * audit event is about.
 *
 * <p>All values use {@code snake_case} for consistency, regardless of
 * the Java class name. The UI mirrors these in
 * {@code ui/src/lib/domain-constants.ts}.</p>
 */
public final class ResourceType {

    private ResourceType() {}

    /** Connection config (UC-018 versioned entity). */
    public static final String CONNECTION_CONFIG = "connection_config";
    /** Instruction asset (UC-KM-02 versioned entity). */
    public static final String INSTRUCTION_ASSET = "instruction_asset";
    /** Knowledge provider (UC-KM-03 versioned entity). */
    public static final String KNOWLEDGE_PROVIDER = "knowledge_provider";
    /** Knowledge source (flat entity within a provider version). */
    public static final String KNOWLEDGE_SOURCE = "knowledge_source";
    /** Data feed (ingestion pipeline config). */
    public static final String DATA_FEED = "data_feed";
    /** Model provider (LLM provider configuration). */
    public static final String MODEL_PROVIDER = "model_provider";
    /** Model (LLM model configuration within a provider). */
    public static final String MODEL = "model";
    /** Secret (vault credential). */
    public static final String SECRET = "secret";
    /** Project (organisational unit for agents and conversations). */
    public static final String PROJECT = "project";
    /** Agent (registered machine agent). */
    public static final String AGENT = "agent";
    /** Conversation (chat session within a project). */
    public static final String CONVERSATION = "conversation";
    /** Conversation message attachment (file upload). */
    public static final String CONVERSATION_ATTACHMENT = "conversation_attachment";
    /** Governance profile (STRICT / STANDARD / FLEXIBLE). */
    public static final String GOVERNANCE_PROFILE = "governance_profile";
    /** System setting (platform-level configuration). */
    public static final String SYSTEM_SETTING = "system_setting";
    /** Project setting (project-level configuration). */
    public static final String PROJECT_SETTING = "project_setting";
    /** Quota (budget / spending limit). */
    public static final String QUOTA = "quota";
    /** User (human operator). */
    public static final String USER = "user";
    /** Audit chain (hash-chain integrity activation). */
    public static final String AUDIT_CHAIN = "audit_chain";
}