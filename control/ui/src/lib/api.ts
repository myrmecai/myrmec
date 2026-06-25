/**
 * API client for Myrmec Control Plane
 */

const API_BASE = '/api/v1'

interface ApiError {
  errorCode: string
  message: string
  details?: unknown
}

class ApiClient {
  private accessToken: string | null = null

  setAccessToken(token: string | null) {
    this.accessToken = token
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown
  ): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    }

    if (this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`
    }

    const response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })

    if (!response.ok) {
      const error: ApiError = await response.json().catch(() => ({
        errorCode: 'UNKNOWN_ERROR',
        message: response.statusText,
      }))
      const retryAfterRaw = response.headers.get('Retry-After')
      const retryAfter = retryAfterRaw ? Number.parseInt(retryAfterRaw, 10) : null
      const apiErr = new ApiRequestError(
        response.status,
        error,
        Number.isFinite(retryAfter as number) ? (retryAfter as number) : null,
      )
      // Phase 8d &mdash; let the in-app banner react to quota blocks
      // without each call site having to know about it.
      if (response.status === 429 && error.errorCode === 'QUOTA_EXCEEDED') {
        try {
          window.dispatchEvent(
            new CustomEvent('myrmec:quota-exceeded', {
              detail: { retryAfter: apiErr.retryAfter, error },
            }),
          )
        } catch {
          // window may be unavailable (tests); ignore.
        }
      }
      throw apiErr
    }

    if (response.status === 204) {
      return undefined as T
    }

    return response.json()
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path)
  }

  /**
   * GET a non-JSON (e.g. text/markdown) resource as raw text, reusing the
   * client's bearer auth. Used for transcript export (#104d) where the
   * engine returns a downloadable document rather than JSON.
   */
  async getText(path: string): Promise<string> {
    const headers: Record<string, string> = {}
    if (this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`
    }
    const response = await fetch(`${API_BASE}${path}`, { method: 'GET', headers })
    if (!response.ok) {
      const error: ApiError = await response.json().catch(() => ({
        errorCode: 'UNKNOWN_ERROR',
        message: response.statusText,
      }))
      throw new ApiRequestError(response.status, error)
    }
    return response.text()
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
  }

  /**
   * POST a multipart/form-data body (e.g. a file upload). The browser sets
   * the `Content-Type` boundary itself, so we deliberately omit it here and
   * only attach the bearer token. Mirrors {@link request}'s error handling.
   */
  async postForm<T>(path: string, form: FormData): Promise<T> {
    const headers: Record<string, string> = {}
    if (this.accessToken) {
      headers['Authorization'] = `Bearer ${this.accessToken}`
    }
    const response = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      headers,
      body: form,
    })
    if (!response.ok) {
      const error: ApiError = await response.json().catch(() => ({
        errorCode: 'UNKNOWN_ERROR',
        message: response.statusText,
      }))
      throw new ApiRequestError(response.status, error)
    }
    if (response.status === 204) {
      return undefined as T
    }
    return response.json()
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PUT', path, body)
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('PATCH', path, body)
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }
}

export class ApiRequestError extends Error {
  constructor(
    public status: number,
    public error: ApiError,
    public retryAfter: number | null = null,
  ) {
    super(error.message)
    this.name = 'ApiRequestError'
  }
}

export const api = new ApiClient()

// Auth API
export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export interface RefreshRequest {
  refreshToken: string
}

export interface RefreshResponse {
  accessToken: string
  accessTokenExpiresAt: string
  roles: string[]
}

export interface ExternalAuthStartResponse {
  providerCode: string
  state: string
  authorizationUrl: string
}

export interface EnabledAuthProvider {
  code: string
  providerType: 'LOCAL' | 'OIDC' | 'GITHUB' | 'GOOGLE'
  name: string
  isEnabled: boolean
}

export const authApi = {
  login: (data: LoginRequest) => api.post<LoginResponse>('/auth/login', data),
  refresh: (data: RefreshRequest) => api.post<RefreshResponse>('/auth/refresh', data),
  listEnabledProviders: () => api.get<EnabledAuthProvider[]>('/auth/providers/enabled'),
  startExternalLogin: (providerCode: string, redirectUri?: string) =>
    api.get<ExternalAuthStartResponse>(
      `/auth/external/${providerCode}/login${
        redirectUri ? `?redirectUri=${encodeURIComponent(redirectUri)}` : ''
      }`
    ),
  completeExternalLogin: (
    providerCode: string,
    state: string,
    code: string,
    redirectUri?: string
  ) =>
    api.get<LoginResponse>(
      `/auth/external/${providerCode}/callback?state=${encodeURIComponent(state)}&code=${encodeURIComponent(code)}${
        redirectUri ? `&redirectUri=${encodeURIComponent(redirectUri)}` : ''
      }`
    ),
}

// Users API
export interface User {
  id: string
  email: string
  name: string
  providerCode: string
  isActive: boolean
  isSystem: boolean
  createdAt: string
  updatedAt: string
  roles?: UserRole[]
}

export interface UserRole {
  id: string
  role: SystemRole
  scopeType: 'SYSTEM' | 'GROUP' | 'PROJECT'
  groupId: string | null
  projectId: string | null
  createdAt: string
}

export interface CreateUserRequest {
  email: string
  name: string
  providerCode: string
  password?: string
}

export interface UpdateUserRequest {
  name?: string
  providerCode?: string
  isActive?: boolean
}

export interface UpdatePasswordRequest {
  newPassword: string
}

export interface AssignRoleRequest {
  role: SystemRole
  scopeType: 'SYSTEM' | 'GROUP' | 'PROJECT'
  groupId?: string
  projectId?: string
}

export const usersApi = {
  list: () => api.get<User[]>('/admin/users'),
  get: (id: string) => api.get<User>(`/admin/users/${id}`),
  create: (data: CreateUserRequest) => api.post<User>('/admin/users', data),
  update: (id: string, data: UpdateUserRequest) => api.patch<User>(`/admin/users/${id}`, data),
  updatePassword: (id: string, data: UpdatePasswordRequest) =>
    api.patch<void>(`/admin/users/${id}/password`, data),
  delete: (id: string) => api.delete<void>(`/admin/users/${id}`),
  assignRole: (userId: string, data: AssignRoleRequest) =>
    api.post<UserRole>(`/admin/users/${userId}/roles`, data),
  removeRole: (userId: string, roleId: string) =>
    api.delete<void>(`/admin/users/${userId}/roles/${roleId}`),
}

// Authentication Providers API
export interface AuthProvider {
  code: string
  providerType: 'LOCAL' | 'OIDC' | 'GITHUB' | 'GOOGLE'
  name: string
  isEnabled: boolean
  isSystem: boolean
  metadata?: Record<string, unknown>
  createdAt?: string
  updatedAt?: string
}

export interface CreateAuthProviderRequest {
  code: string
  providerType: 'LOCAL' | 'OIDC' | 'GITHUB' | 'GOOGLE'
  name: string
  isEnabled: boolean
  metadata?: Record<string, unknown>
}

export interface UpdateAuthProviderRequest {
  name?: string
  isEnabled?: boolean
  metadata?: Record<string, unknown>
}

export const authProvidersApi = {
  list: () => api.get<AuthProvider[]>('/admin/auth-providers'),
  create: (data: CreateAuthProviderRequest) =>
    api.post<AuthProvider>('/admin/auth-providers', data),
  update: (code: string, data: UpdateAuthProviderRequest) =>
    api.patch<AuthProvider>(`/admin/auth-providers/${code}`, data),
  enable: (code: string) => api.post<AuthProvider>(`/admin/auth-providers/${code}/enable`),
  disable: (code: string) => api.post<AuthProvider>(`/admin/auth-providers/${code}/disable`),
  validate: (code: string) => api.post<AuthProvider>(`/admin/auth-providers/${code}/validate`),
}

// Projects API
export type ProjectStatus = 'ACTIVE' | 'INACTIVE'

export interface RagConfig {
  endpoint?: string
  apiKeySecret?: string
  collection?: string
  topK?: number
}

export interface Project {
  id: string
  name: string
  description: string | null
  groupId: string
  status: ProjectStatus
  allowedServiceTypes: string[]
  workspaceRepoUrl: string | null
  workspaceRepoBranch: string | null
  workspaceCredentialSecretId: string | null
  ragConfig: RagConfig | null
  createdAt: string
  updatedAt: string | null
}

export interface CreateProjectRequest {
  name: string
  description?: string
  groupId?: string
  allowedServiceTypes?: string[]
  workspaceRepoUrl?: string
  workspaceRepoBranch?: string
  workspaceCredentialSecretId?: string | null
  ragConfig?: RagConfig
}

export interface UpdateProjectRequest {
  name?: string
  description?: string
  status?: ProjectStatus
  allowedServiceTypes?: string[]
  workspaceRepoUrl?: string
  workspaceRepoBranch?: string
  workspaceCredentialSecretId?: string | null
  ragConfig?: RagConfig
}

export const projectsApi = {
  list: () => api.get<Project[]>('/projects'),
  get: (id: string) => api.get<Project>(`/projects/${id}`),
  create: (data: CreateProjectRequest) => api.post<Project>('/projects', data),
  update: (id: string, data: UpdateProjectRequest) => api.put<Project>(`/projects/${id}`, data),
  delete: (id: string) => api.delete<void>(`/projects/${id}`),
  moveToGroup: (id: string, groupId: string) =>
    api.post<Project>(`/projects/${id}/move-group`, { groupId }),
}

// Groups API
export interface Group {
  id: string
  name: string
  description: string | null
  parentGroupId: string | null
  createdAt: string
  updatedAt: string | null
}

export interface CreateGroupRequest {
  name: string
  description?: string
  parentGroupId?: string
}

export interface UpdateGroupRequest {
  name?: string
  description?: string
}

export interface GroupMember {
  roleId: string
  userId: string
  email: string
  name: string | null
  role: SystemRole
}

export interface AssignGroupMemberRequest {
  userId: string
  role: SystemRole
}

export const groupsApi = {
  list: () => api.get<Group[]>('/admin/groups'),
  get: (id: string) => api.get<Group>(`/admin/groups/${id}`),
  create: (data: CreateGroupRequest) => api.post<Group>('/admin/groups', data),
  update: (id: string, data: UpdateGroupRequest) =>
    api.patch<Group>(`/admin/groups/${id}`, data),
  delete: (id: string) => api.delete<void>(`/admin/groups/${id}`),
  listMembers: (id: string) => api.get<GroupMember[]>(`/admin/groups/${id}/members`),
  assignMember: (id: string, data: AssignGroupMemberRequest) =>
    api.post<GroupMember>(`/admin/groups/${id}/members`, data),
  removeMember: (groupId: string, roleId: string) =>
    api.delete<void>(`/admin/groups/${groupId}/members/${roleId}`),
}

// Project Members API
export type ProjectMemberRole =
  | 'PROJECT_OWNER'
  | 'EDITOR'
  | 'VIEWER'
  | 'BUDGET_OWNER'
  | 'APPROVER'
  | 'AUDITOR'
export type SystemRole =
  | 'PLATFORM_ADMIN'
  | 'ORG_ADMIN'
  | 'PROJECT_OWNER'
  | 'EDITOR'
  | 'VIEWER'
  | 'BUDGET_OWNER'
  | 'APPROVER'
  | 'AUDITOR'
/** @deprecated use {@link SystemRole}. Kept for legacy code paths. */
export type SystemWideRole = SystemRole

export interface ProjectMember {
  userId: string
  email: string
  name: string | null
  isActive: boolean
  role: ProjectMemberRole
  projectRoleId: string
  grantedByUserId: string | null
  grantedByEmail: string | null
  createdAt: string
}

export interface SystemWideUser {
  userId: string
  email: string
  name: string | null
  role: SystemRole
}

export interface ProjectMemberListResponse {
  projectMembers: ProjectMember[]
  systemWideUsers: SystemWideUser[]
}

export interface ProjectMemberCandidate {
  userId: string
  email: string
  name: string | null
}

export interface AssignProjectMemberRequest {
  userId: string
  role: ProjectMemberRole
}

export const projectMembersApi = {
  list: (projectId: string) =>
    api.get<ProjectMemberListResponse>(`/projects/${projectId}/members`),
  listCandidates: (projectId: string) =>
    api.get<ProjectMemberCandidate[]>(`/projects/${projectId}/members/candidates`),
  assign: (projectId: string, data: AssignProjectMemberRequest) =>
    api.post<ProjectMember>(`/projects/${projectId}/members`, data),
  remove: (projectId: string, userId: string, roleId: string) =>
    api.delete<void>(`/projects/${projectId}/members/${userId}/roles/${roleId}`),
}

// Secrets API
export type CredentialType =
  | 'USERNAME_PASSWORD'
  | 'BEARER_TOKEN'
  | 'API_KEY'
  | 'SECRET_KEY'
  | 'OAUTH_CLIENT'
  | 'SSL_PRIVATE_KEY'
  | 'CUSTOM'

export type SecretBackend = 'LOCAL' | 'VAULT' | 'AWS_SECRETS_MANAGER' | 'AZURE_KEY_VAULT'

export type SecretScope = 'GLOBAL' | 'PROJECT'

/** Discriminated union — `type` field matches the Jackson @JsonTypeInfo wire format. */
export type SecretPayload =
  | { type: 'USERNAME_PASSWORD'; username: string; password: string }
  | { type: 'BEARER_TOKEN'; token: string }
  | { type: 'API_KEY'; key: string; header?: string | null }
  | { type: 'SECRET_KEY'; secret: string }
  | { type: 'OAUTH_CLIENT'; clientId: string; clientSecret: string }
  | { type: 'SSL_PRIVATE_KEY'; privateKey: string; certificate?: string | null; passphrase?: string | null }
  | { type: 'CUSTOM'; data: Record<string, unknown> }

export interface Secret {
  id: string
  name: string
  type: CredentialType
  scope: SecretScope
  backend: SecretBackend
  projectId: string | null
  projectName: string | null
  createdById: string | null
  createdByEmail: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateSecretRequest {
  name: string
  type: CredentialType
  backend?: SecretBackend
  payload: SecretPayload
}

export interface UpdateSecretRequest {
  payload: SecretPayload
}

export const projectSecretsApi = {
  list: (projectId: string) => api.get<Secret[]>(`/projects/${projectId}/secrets`),
  get: (projectId: string, id: string) => api.get<Secret>(`/projects/${projectId}/secrets/${id}`),
  create: (projectId: string, data: CreateSecretRequest) =>
    api.post<Secret>(`/projects/${projectId}/secrets`, data),
  update: (projectId: string, id: string, data: UpdateSecretRequest) =>
    api.put<Secret>(`/projects/${projectId}/secrets/${id}`, data),
  delete: (projectId: string, id: string) =>
    api.delete<void>(`/projects/${projectId}/secrets/${id}`),
}

export const globalSecretsApi = {
  list: () => api.get<Secret[]>('/admin/secrets'),
  get: (id: string) => api.get<Secret>(`/admin/secrets/${id}`),
  create: (data: CreateSecretRequest) => api.post<Secret>('/admin/secrets', data),
  update: (id: string, data: UpdateSecretRequest) =>
    api.put<Secret>(`/admin/secrets/${id}`, data),
  delete: (id: string) => api.delete<void>(`/admin/secrets/${id}`),
}

// Knowledge bases (RAG) API
export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  scope: string
  projectId: string | null
  providerId: string | null
  status: string | null
  classification: string | null
  allowAssistantBinding: boolean
  sourceCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeBaseRequest {
  name: string
  description?: string | null
  providerId?: string | null
  classification?: string | null
}

export interface KnowledgeSource {
  id: string
  knowledgeBaseId: string
  connectorType: string
  name: string
  uri: string
  syncSchedule: string | null
  enabled: boolean
  lastSyncAt: string | null
  lastSyncStatus: string | null
  lastSyncChunks: number | null
  lastSyncErrorCount: number | null
  lastSyncDurationMs: number | null
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface CreateKnowledgeSourceRequest {
  connectorType: string
  name: string
  uri: string
  configJson?: string | null
  syncSchedule?: string | null
}

export interface SyncResult {
  status: string
  chunksEmitted: number
  errorCount: number
  errors: string[]
  durationMs: number
  completedAt: string
}

export interface KnowledgeCapabilities {
  connectorTypes: string[]
  providerIds: string[]
}

// #30 — user-facing chunk-context preview for the citation side panel.
export interface ChunkContext {
  chunkId: string
  sourceId: string
  sourceName: string
  locator: string
  passage: string
  before: string[]
  after: string[]
}

export const knowledgeBasesApi = {
  list: (projectId: string) =>
    api.get<KnowledgeBase[]>(`/projects/${projectId}/knowledge-bases`),
  get: (projectId: string, kbId: string) =>
    api.get<KnowledgeBase>(`/projects/${projectId}/knowledge-bases/${kbId}`),
  create: (projectId: string, data: CreateKnowledgeBaseRequest) =>
    api.post<KnowledgeBase>(`/projects/${projectId}/knowledge-bases`, data),
  delete: (projectId: string, kbId: string) =>
    api.delete<void>(`/projects/${projectId}/knowledge-bases/${kbId}`),
  listSources: (projectId: string, kbId: string) =>
    api.get<KnowledgeSource[]>(`/projects/${projectId}/knowledge-bases/${kbId}/sources`),
  addSource: (projectId: string, kbId: string, data: CreateKnowledgeSourceRequest) =>
    api.post<KnowledgeSource>(`/projects/${projectId}/knowledge-bases/${kbId}/sources`, data),
  deleteSource: (projectId: string, kbId: string, sourceId: string) =>
    api.delete<void>(`/projects/${projectId}/knowledge-bases/${kbId}/sources/${sourceId}`),
  sync: (projectId: string, kbId: string, sourceId: string) =>
    api.post<SyncResult>(
      `/projects/${projectId}/knowledge-bases/${kbId}/sources/${sourceId}/sync`,
      {},
    ),
  capabilities: () => api.get<KnowledgeCapabilities>('/knowledge/capabilities'),
  // #30 — fetch the cited chunk plus its neighbouring passages for the side panel.
  chunkContext: (projectId: string, kbId: string, chunkId: string) =>
    api.get<ChunkContext>(
      `/projects/${projectId}/knowledge-bases/${kbId}/chunks/${chunkId}/context`,
    ),
}

// Providers API
export type DeploymentType = 'CLOUD' | 'ON_PREMISE'
export type ModelStatus = 'ACTIVE' | 'INACTIVE'
export type HealthStatus = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'UNKNOWN' | 'LOADING'

export interface ModelProviderConfig {
  code: string
  name: string
  baseUrl: string | null
  deploymentType: DeploymentType
  requiresAuth: boolean
  authHeader?: string
  authPrefix?: string
  healthEndpoint?: string | null
  modelsEndpoint?: string | null
  docsUrl?: string | null
  description?: string | null
  isSystem?: boolean
  status: ModelStatus
  createdAt?: string
  updatedAt?: string | null
}

export const providersApi = {
  // Public endpoints
  list: () => api.get<ModelProviderConfig[]>('/providers'),
  get: (code: string) => api.get<ModelProviderConfig>(`/providers/${code}`),
  // Admin endpoints
  listAll: () => api.get<ModelProviderConfig[]>('/admin/providers'),
  getDetails: (code: string) => api.get<ModelProviderConfig>(`/admin/providers/${code}`),
  // Phase 10 #70 — admin CRUD on providers (read endpoints above).
  create: (req: CreateModelProviderRequest) =>
    api.post<ModelProviderConfig>('/admin/providers', req),
  update: (code: string, req: UpdateModelProviderRequest) =>
    api.put<ModelProviderConfig>(`/admin/providers/${code}`, req),
  delete: (code: string) => api.delete<void>(`/admin/providers/${code}`),
}

export interface CreateModelProviderRequest {
  code: string
  name: string
  baseUrl?: string | null
  deploymentType: DeploymentType
  requiresAuth: boolean
  authHeader?: string | null
  authPrefix?: string | null
  healthEndpoint?: string | null
  modelsEndpoint?: string | null
  docsUrl?: string | null
  description?: string | null
}

export interface UpdateModelProviderRequest {
  name?: string
  baseUrl?: string | null
  deploymentType?: DeploymentType
  requiresAuth?: boolean
  authHeader?: string | null
  authPrefix?: string | null
  healthEndpoint?: string | null
  modelsEndpoint?: string | null
  docsUrl?: string | null
  description?: string | null
  status?: ModelStatus
}

// Models API
export interface Model {
  code: string
  name: string
  provider: string
  providerName: string
  deploymentType: DeploymentType
  modelId: string
  apiEndpoint: string | null
  requiresAuth: boolean
  supportsVision: boolean
  infraConfig: Record<string, unknown> | null
  defaultParams: Record<string, unknown> | null
  status: ModelStatus
  healthStatus: HealthStatus
  lastHealthCheck: string | null
  lastTestedAt: string | null
  lastTestStatus: string | null
  createdAt: string
  updatedAt: string | null
}

export interface CreateModelRequest {
  code: string
  name: string
  provider: string
  deploymentType: DeploymentType
  modelId: string
  apiEndpoint?: string
  apiKey?: string
  requiresAuth?: boolean
  supportsVision?: boolean
  infraConfig?: Record<string, unknown>
  defaultParams?: Record<string, unknown>
}

export interface UpdateModelRequest {
  name?: string
  apiEndpoint?: string
  apiKey?: string
  requiresAuth?: boolean
  supportsVision?: boolean
  infraConfig?: Record<string, unknown>
  defaultParams?: Record<string, unknown>
  status?: ModelStatus
}

export interface TestModelResponse {
  status: string
  latencyMs: number
  message: string
  testedAt: string
}

export interface ModelHealthResponse {
  code: string
  status: HealthStatus
  lastCheck: string | null
  metrics?: Record<string, unknown>
}

export const modelsApi = {
  // Admin endpoints
  list: () => api.get<Model[]>('/admin/models'),
  get: (code: string) => api.get<Model>(`/admin/models/${code}`),
  create: (data: CreateModelRequest) => api.post<Model>('/admin/models', data),
  update: (code: string, data: UpdateModelRequest) => api.put<Model>(`/admin/models/${code}`, data),
  delete: (code: string) => api.delete<void>(`/admin/models/${code}`),
  test: (code: string) => api.post<TestModelResponse>(`/admin/models/${code}/test`),
  health: (code: string) => api.get<ModelHealthResponse>(`/admin/models/${code}/health`),
  // Public endpoint
  listActive: () => api.get<Model[]>('/models'),
}

// Agent Profiles API
export type AgentProfileStatus = 'ACTIVE' | 'INACTIVE'

export interface AgentProfile {
  id: string
  name: string
  description: string | null
  capabilities: string[]
  supportedTools: string[]
  toolCodes: string[]
  systemPrompt: string | null
  defaultModel: string | null
  status: AgentProfileStatus
  createdAt: string
  updatedAt: string | null
}

export interface CreateAgentProfileRequest {
  name: string
  description?: string
  capabilities?: string[]
  supportedTools?: string[]
  toolCodes?: string[]
  systemPrompt?: string
  defaultModel?: string
}

export interface UpdateAgentProfileRequest {
  name: string
  description?: string
  capabilities?: string[]
  supportedTools?: string[]
  toolCodes?: string[]
  systemPrompt?: string
  defaultModel?: string
}

export const agentProfilesApi = {
  list: (activeOnly = false) =>
    api.get<AgentProfile[]>(`/admin/agent-profiles?activeOnly=${activeOnly}`),
  get: (id: string) => api.get<AgentProfile>(`/admin/agent-profiles/${id}`),
  create: (data: CreateAgentProfileRequest) =>
    api.post<AgentProfile>('/admin/agent-profiles', data),
  update: (id: string, data: UpdateAgentProfileRequest) =>
    api.put<AgentProfile>(`/admin/agent-profiles/${id}`, data),
  delete: (id: string) => api.delete<void>(`/admin/agent-profiles/${id}`),
  deactivate: (id: string) => api.post<void>(`/admin/agent-profiles/${id}/deactivate`),
  activate: (id: string) => api.post<void>(`/admin/agent-profiles/${id}/activate`),
}

// Agents API
export type AgentStatus = 'ACTIVE' | 'INACTIVE'

export interface Agent {
  id: string
  name: string
  description: string | null
  profileId: string
  profileName: string | null
  projectId: string | null
  projectName: string | null
  modelOverride: string | null
  config: Record<string, unknown> | null
  maxAgents: number
  status: AgentStatus
  activeInstanceCount: number
  createdAt: string
  updatedAt: string | null
}

export interface AgentWithKey {
  agent: Agent
  registrationKey: string
}

export interface CreateAgentRequest {
  name: string
  description?: string
  profileId: string
  projectId?: string
  modelOverride?: string
  config?: Record<string, unknown>
  maxAgents?: number
}

export interface UpdateAgentRequest {
  name?: string
  description?: string
  profileId?: string
  projectId?: string
  modelOverride?: string
  config?: Record<string, unknown>
  maxAgents?: number
  status?: AgentStatus
}

export const agentsApi = {
  list: () => api.get<Agent[]>('/admin/agents'),
  get: (id: string) => api.get<Agent>(`/admin/agents/${id}`),
  create: (data: CreateAgentRequest) => api.post<AgentWithKey>('/admin/agents', data),
  update: (id: string, data: UpdateAgentRequest) =>
    api.put<Agent>(`/admin/agents/${id}`, data),
  delete: (id: string) => api.delete<void>(`/admin/agents/${id}`),
  regenerateKey: (id: string) =>
    api.post<{ registrationKey: string }>(`/admin/agents/${id}/regenerate-key`),
  /**
   * List the ephemeral worker replicas of an agent host together with their
   * runtime FSM status (IDLE/RESERVED/CONNECTING/BOUND/DRAINING/DEAD).
   */
  workers: (id: string) => api.get<AgentWorker[]>(`/admin/agents/${id}/workers`),
  /**
   * Aggregated runtime-health snapshot for an agent host: online/idle/busy/
   * stale instance counts, total queue depth, and heartbeat freshness (#69).
   */
  health: (id: string) => api.get<AgentHealthSnapshot>(`/admin/agents/${id}/health`),
}

// Aggregated agent-host health (#69). Mirrors engine AgentHealthSnapshot.
export interface AgentInstanceHealth {
  instanceId: string
  hostname: string | null
  runtimeVersion: string | null
  status: string
  idle: boolean
  stale: boolean
  registeredAt: string | null
  lastHeartbeatAt: string | null
  secondsSinceHeartbeat: number | null
  activeAttempts: number
}

export interface AgentHealthSnapshot {
  agentId: string
  totalInstances: number
  onlineInstances: number
  idleInstances: number
  busyInstances: number
  staleInstances: number
  queueDepth: number
  latestHeartbeatAt: string | null
  instances: AgentInstanceHealth[]
}

// The runtime FSM of an ephemeral worker replica (agent-concurrency §9.5).
export type AgentWorkerStatus =
  | 'IDLE'
  | 'RESERVED'
  | 'CONNECTING'
  | 'BOUND'
  | 'DRAINING'
  | 'DEAD'

export interface AgentWorker {
  id: string
  agentHostId: string
  conversationId: string | null
  hostname: string | null
  ipAddress: string | null
  runtimeVersion: string | null
  status: AgentWorkerStatus | null
  registeredAt: string | null
  lastHeartbeatAt: string | null
  stateChangedAt: string | null
}

// Tools API
export type ToolType = 'SYSTEM' | 'INTEGRATION' | 'DATABASE' | 'CUSTOM'
export type ToolStatus = 'ACTIVE' | 'DISABLED' | 'DEPRECATED'

export interface Tool {
  code: string
  name: string
  description: string | null
  toolType: ToolType
  configSchema: Record<string, unknown> | null
  docsUrl: string | null
  isSystem: boolean
  status: ToolStatus
  createdAt: string
  updatedAt: string
}

export interface CreateToolRequest {
  code: string
  name: string
  description?: string
  toolType: ToolType
  configSchema?: Record<string, unknown>
  docsUrl?: string
}

export interface UpdateToolRequest {
  name: string
  description?: string
  toolType: ToolType
  configSchema?: Record<string, unknown>
  docsUrl?: string
  status: ToolStatus
}

export const toolsApi = {
  list: () => api.get<Tool[]>('/admin/tools'),
  listActive: () => api.get<Tool[]>('/admin/tools/active'),
  get: (code: string) => api.get<Tool>(`/admin/tools/${code}`),
  create: (data: CreateToolRequest) => api.post<Tool>('/admin/tools', data),
  update: (code: string, data: UpdateToolRequest) =>
    api.put<Tool>(`/admin/tools/${code}`, data),
  delete: (code: string) => api.delete<void>(`/admin/tools/${code}`),
}

// Service Types API (#78 — read-only platform registry)
export interface ServiceType {
  code: string
  displayName: string
  description: string
  icon: string
  enabled: boolean
  projectEnabledCount: number
}

export const serviceTypesApi = {
  list: () => api.get<ServiceType[]>('/admin/service-types'),
}

// Workflows API
export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED' | 'ARCHIVED'
export type RequestStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'
export type TaskStatus = 'PENDING' | 'READY' | 'RUNNING' | 'COMPLETED' | 'CANCELLED'
export type TaskResult = 'SUCCESS' | 'FAILURE' | 'TIMEOUT'

export interface WorkflowStep {
  id: string
  name: string
  agentProfileId: string
  prompt?: string
  dependsOn?: string[]
  transitions?: Record<string, string>
  timeoutSeconds?: number
  maxRetries?: number
}

export interface ArtifactsRepo {
  url: string
  baseBranch?: string | null
  credentialSecretId?: string | null
}

export interface Workflow {
  id: string
  projectId: string
  projectName: string
  name: string
  description: string | null
  steps: WorkflowStep[]
  inputSchema: Record<string, unknown> | null
  artifactsRepo: ArtifactsRepo | null
  version: number
  status: WorkflowStatus
  createdById: string
  createdByEmail: string
  createdAt: string
  updatedAt: string
}

export interface CreateWorkflowRequest {
  projectId: string
  name: string
  description?: string
  steps: WorkflowStep[]
  inputSchema?: Record<string, unknown>
  artifactsRepo?: ArtifactsRepo | null
}

export interface UpdateWorkflowRequest {
  name: string
  description?: string
  steps: WorkflowStep[]
  inputSchema?: Record<string, unknown>
  artifactsRepo?: ArtifactsRepo | null
  status?: WorkflowStatus
}

export interface WorkflowRequest {
  id: string
  workflowId: string
  workflowName: string
  workflowVersion: number
  input: Record<string, unknown> | null
  output: Record<string, unknown> | null
  status: RequestStatus
  errorMessage: string | null
  createdById: string
  createdByEmail: string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface StartWorkflowRequest {
  workflowId: string
  input?: Record<string, unknown>
}

export interface TaskMetrics {
  model?: string | null
  modelCallCount?: number | null
  toolCallCount?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  totalTokens?: number | null
  modelDurationMs?: number | null
  toolDurationMs?: number | null
  totalDurationMs?: number | null
  costUsd?: number | null
  currency?: string | null
}

export interface WorkflowTask {
  id: string
  requestId: string
  stepId: string
  agentProfileId: string
  agentProfileName: string
  agentInstanceId: string | null
  input: Record<string, unknown> | null
  output: Record<string, unknown> | null
  status: TaskStatus
  result: TaskResult | null
  errorMessage: string | null
  attempt: number
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  metrics: TaskMetrics | null
}

// Execution Events
export type EventType = 'LOG' | 'PROGRESS' | 'TOOL_CALL' | 'TOOL_RESULT' | 'STATUS_CHANGE' | 'TOKEN_USAGE' | 'TASK_METRICS'
export type LogSource = 'TASK' | 'AGENT' | 'SYSTEM'

export interface ExecutionEvent {
  id: string
  taskId: string
  eventType: EventType
  logLevel: string | null
  message: string | null
  data: Record<string, unknown> | null
  progress: number | null
  toolName: string | null
  toolCallId: string | null
  durationMs: number | null
  isError: boolean | null
  source: LogSource | null
  createdAt: string
}

export const workflowsApi = {
  list: (projectId: string) =>
    api.get<Workflow[]>(`/projects/${projectId}/workflows`),
  listPublished: (projectId: string) =>
    api.get<Workflow[]>(`/projects/${projectId}/workflows/published`),
  get: (projectId: string, id: string) =>
    api.get<Workflow>(`/projects/${projectId}/workflows/${id}`),
  create: (projectId: string, data: CreateWorkflowRequest) =>
    api.post<Workflow>(`/projects/${projectId}/workflows`, data),
  update: (projectId: string, id: string, data: UpdateWorkflowRequest) =>
    api.put<Workflow>(`/projects/${projectId}/workflows/${id}`, data),
  delete: (projectId: string, id: string) =>
    api.delete<void>(`/projects/${projectId}/workflows/${id}`),
  publish: (projectId: string, id: string) =>
    api.post<Workflow>(`/projects/${projectId}/workflows/${id}/publish`),
  archive: (projectId: string, id: string) =>
    api.post<Workflow>(`/projects/${projectId}/workflows/${id}/archive`),
  // Requests
  listRequests: (projectId: string, workflowId: string) =>
    api.get<WorkflowRequest[]>(`/projects/${projectId}/workflows/${workflowId}/requests`),
  getRequest: (projectId: string, workflowId: string, requestId: string) =>
    api.get<WorkflowRequest>(`/projects/${projectId}/workflows/${workflowId}/requests/${requestId}`),
  startRequest: (projectId: string, workflowId: string, data: StartWorkflowRequest) =>
    api.post<WorkflowRequest>(`/projects/${projectId}/workflows/${workflowId}/requests`, data),
  cancelRequest: (projectId: string, workflowId: string, requestId: string) =>
    api.post<WorkflowRequest>(`/projects/${projectId}/workflows/${workflowId}/requests/${requestId}/cancel`),
  // Tasks
  listTasks: (projectId: string, workflowId: string, requestId: string) =>
    api.get<WorkflowTask[]>(`/projects/${projectId}/workflows/${workflowId}/requests/${requestId}/tasks`),
  // Events
  listEvents: (projectId: string, workflowId: string, requestId: string, after?: string) =>
    api.get<ExecutionEvent[]>(
      `/projects/${projectId}/workflows/${workflowId}/requests/${requestId}/events${after ? `?after=${after}` : ''}`
    ),
  listTaskEvents: (projectId: string, workflowId: string, requestId: string, taskId: string, after?: string) =>
    api.get<ExecutionEvent[]>(
      `/projects/${projectId}/workflows/${workflowId}/requests/${requestId}/events/tasks/${taskId}${after ? `?after=${after}` : ''}`
    ),
}

// Cross-project workflow list view
export interface WorkflowListItem {
  id: string
  name: string
  description: string | null
  version: number
  status: WorkflowStatus
  projectId: string
  projectName: string
  createdByEmail: string
  createdAt: string
  updatedAt: string
  lastRunId: string | null
  lastRunStatus: RequestStatus | null
  lastRunAt: string | null
}

export interface PagedResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AccessibleProject {
  id: string
  name: string
}

export type WorkflowSortField =
  | 'name'
  | 'status'
  | 'projectName'
  | 'createdAt'
  | 'updatedAt'
  | 'lastRunAt'

export type SortDirection = 'asc' | 'desc'

export interface WorkflowListParams {
  status?: WorkflowStatus[]
  projectId?: string[]
  search?: string
  lastRunStatus?: (RequestStatus | 'NEVER')[]
  createdFrom?: string
  createdTo?: string
  lastRunFrom?: string
  lastRunTo?: string
  sort?: WorkflowSortField
  direction?: SortDirection
  page?: number
  size?: number
}

function buildWorkflowListQuery(params: WorkflowListParams): string {
  const search = new URLSearchParams()
  params.status?.forEach((s) => search.append('status', s))
  params.projectId?.forEach((id) => search.append('projectId', id))
  if (params.search?.trim()) search.set('search', params.search.trim())
  params.lastRunStatus?.forEach((s) => search.append('lastRunStatus', s))
  if (params.createdFrom) search.set('createdFrom', params.createdFrom)
  if (params.createdTo) search.set('createdTo', params.createdTo)
  if (params.lastRunFrom) search.set('lastRunFrom', params.lastRunFrom)
  if (params.lastRunTo) search.set('lastRunTo', params.lastRunTo)
  if (params.sort) search.set('sort', params.sort)
  if (params.direction) search.set('direction', params.direction)
  if (params.page !== undefined) search.set('page', String(params.page))
  if (params.size !== undefined) search.set('size', String(params.size))
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

export const workflowsListApi = {
  list: (params: WorkflowListParams) =>
    api.get<PagedResponse<WorkflowListItem>>(`/workflows${buildWorkflowListQuery(params)}`),
  accessibleProjects: () =>
    api.get<AccessibleProject[]>('/workflows/accessible-projects'),
}

// ============================================================================
// Assistants API (#92, #93) — the conversational service type. Parent-row CRUD
// + kill switches, the Draft/Publish version lifecycle, and the
// `assistant_grants` ACL. Mirrors the engine's `/api/v1/assistants` surface.
// ============================================================================

export type AssistantVersionStatus = 'DRAFT' | 'PUBLISHED'
export type AssistantBumpType = 'PATCH' | 'MINOR' | 'MAJOR'
export type AssistantHitlOverrideMode = 'INHERIT' | 'STRICT'
export type AssistantUsableVia = 'WEB_UI' | 'EXTERNAL_API'
export type AssistantGrantPrincipalType = 'USER' | 'ROLE' | 'SERVICE_ACCOUNT'
export type AssistantGrantPermission = 'OWNER' | 'EDITOR' | 'VIEWER' | 'USE'

/** Parent-row view of an assistant. */
export interface Assistant {
  id: string
  projectId: string
  name: string
  description: string | null
  currentVersionId: string | null
  disabled: boolean
  archivedAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string | null
}

/** Full view of an assistant version (Draft or Published). */
export interface AssistantVersion {
  id: string
  assistantId: string
  versionNumber: string | null
  bumpType: AssistantBumpType | null
  status: AssistantVersionStatus | null
  parentVersionId: string | null
  draftOwnerId: string | null
  agentProfileId: string | null
  agentProfileVersionId: string | null
  addendum: string | null
  greetingMessage: string | null
  maxIdleMinutes: number
  maxSessionAgeHours: number | null
  kbBindings: string[] | null
  disabledTools: string[] | null
  hitlOverrideMode: AssistantHitlOverrideMode | null
  usableVia: string[] | null
  attachmentsEnabled: boolean
  attachmentRetentionTtl: number | null
  attachmentMaxFileSize: number | null
  attachmentTypeAllowlist: string | null
  publishedAt: string | null
  publishedBy: string | null
  createdAt: string
  updatedAt: string | null
}

/** One ACL grant entry. */
export interface AssistantGrant {
  id: string
  assistantId: string
  principalType: AssistantGrantPrincipalType
  principalId: string
  permission: AssistantGrantPermission
  grantedAt: string
  grantedBy: string | null
}

export interface CreateAssistantRequest {
  projectId: string
  name: string
  description?: string
  agentProfileId: string
}

export interface UpdateAssistantRequest {
  name?: string
  description?: string
}

/** Zone-2 Draft edit — every field optional; null leaves the value unchanged. */
export interface UpdateAssistantDraftRequest {
  agentProfileId?: string
  addendum?: string
  greetingMessage?: string
  maxIdleMinutes?: number
  maxSessionAgeHours?: number
  kbBindings?: string[]
  disabledTools?: string[]
  hitlOverrideMode?: AssistantHitlOverrideMode
  usableVia?: string[]
  attachmentsEnabled?: boolean
  attachmentRetentionTtl?: number
  attachmentMaxFileSize?: number
  attachmentTypeAllowlist?: string
}

export interface AddAssistantGrantRequest {
  principalType: AssistantGrantPrincipalType
  principalId: string
  permission: AssistantGrantPermission
}

export const assistantsApi = {
  // Parent-row CRUD + kill switches
  list: (projectId: string) =>
    api.get<Assistant[]>(`/assistants?projectId=${projectId}`),
  get: (id: string) => api.get<Assistant>(`/assistants/${id}`),
  create: (data: CreateAssistantRequest) =>
    api.post<Assistant>('/assistants', data),
  update: (id: string, data: UpdateAssistantRequest) =>
    api.patch<Assistant>(`/assistants/${id}`, data),
  setDisabled: (id: string, disabled: boolean) =>
    api.post<Assistant>(`/assistants/${id}/disable`, { disabled }),
  archive: (id: string) => api.post<Assistant>(`/assistants/${id}/archive`),
  unarchive: (id: string) => api.post<Assistant>(`/assistants/${id}/unarchive`),
  // Version lifecycle
  listVersions: (id: string) =>
    api.get<AssistantVersion[]>(`/assistants/${id}/versions`),
  getDraft: (id: string) =>
    api.get<AssistantVersion>(`/assistants/${id}/draft`),
  openDraft: (id: string) =>
    api.post<AssistantVersion>(`/assistants/${id}/versions`),
  updateDraft: (id: string, data: UpdateAssistantDraftRequest) =>
    api.patch<AssistantVersion>(`/assistants/${id}/draft`, data),
  discardDraft: (id: string) => api.delete<void>(`/assistants/${id}/draft`),
  takeOverDraft: (id: string) =>
    api.post<AssistantVersion>(`/assistants/${id}/draft/takeover`),
  publish: (id: string) =>
    api.post<AssistantVersion>(`/assistants/${id}/publish`),
  // Grants ACL
  listGrants: (id: string) =>
    api.get<AssistantGrant[]>(`/assistants/${id}/grants`),
  addGrant: (id: string, data: AddAssistantGrantRequest) =>
    api.post<AssistantGrant>(`/assistants/${id}/grants`, data),
  removeGrant: (id: string, grantId: string) =>
    api.delete<void>(`/assistants/${id}/grants/${grantId}`),
}

// Knowledge Documents API
export type KnowledgeScope = 'ORGANIZATION' | 'PROJECT'
export type KnowledgeCategory = 'STANDARD' | 'INSTRUCTION' | 'REQUIREMENT' | 'ARCHITECTURE'

export interface KnowledgeDocument {
  id: string
  scope: KnowledgeScope
  projectId: string | null
  category: KnowledgeCategory
  name: string
  content: string
  priority: number
  appliesTo: string[] | null
  sourcePath: string | null
  active: boolean
  createdBy: string
  createdAt: string
  updatedAt: string | null
}

export interface CreateKnowledgeDocumentRequest {
  category: KnowledgeCategory
  name: string
  content: string
  priority?: number
  appliesTo?: string[]
  sourcePath?: string
}

export interface UpdateKnowledgeDocumentRequest {
  category: KnowledgeCategory
  name: string
  content: string
  priority?: number
  appliesTo?: string[]
  sourcePath?: string
}

export const knowledgeApi = {
  // Organization-level knowledge (admin)
  listOrganization: () => api.get<KnowledgeDocument[]>('/admin/knowledge'),
  getOrganization: (id: string) => api.get<KnowledgeDocument>(`/admin/knowledge/${id}`),
  createOrganization: (data: CreateKnowledgeDocumentRequest) =>
    api.post<KnowledgeDocument>('/admin/knowledge', data),
  updateOrganization: (id: string, data: UpdateKnowledgeDocumentRequest) =>
    api.put<KnowledgeDocument>(`/admin/knowledge/${id}`, data),
  deleteOrganization: (id: string) => api.delete<void>(`/admin/knowledge/${id}`),
  activateOrganization: (id: string) =>
    api.post<KnowledgeDocument>(`/admin/knowledge/${id}/activate`),
  deactivateOrganization: (id: string) =>
    api.post<KnowledgeDocument>(`/admin/knowledge/${id}/deactivate`),

  // Project-level knowledge
  listProject: (projectId: string) =>
    api.get<KnowledgeDocument[]>(`/projects/${projectId}/knowledge`),
  getProject: (projectId: string, id: string) =>
    api.get<KnowledgeDocument>(`/projects/${projectId}/knowledge/${id}`),
  createProject: (projectId: string, data: CreateKnowledgeDocumentRequest) =>
    api.post<KnowledgeDocument>(`/projects/${projectId}/knowledge`, data),
  updateProject: (projectId: string, id: string, data: UpdateKnowledgeDocumentRequest) =>
    api.put<KnowledgeDocument>(`/projects/${projectId}/knowledge/${id}`, data),
  deleteProject: (projectId: string, id: string) =>
    api.delete<void>(`/projects/${projectId}/knowledge/${id}`),
  activateProject: (projectId: string, id: string) =>
    api.post<KnowledgeDocument>(`/projects/${projectId}/knowledge/${id}/activate`),
  deactivateProject: (projectId: string, id: string) =>
    api.post<KnowledgeDocument>(`/projects/${projectId}/knowledge/${id}/deactivate`),
}

// Project Knowledge Repos API
export interface ProjectKnowledgeRepo {
  id: string
  projectId: string
  name: string
  repoUrl: string
  branch: string
  instructionPaths: string[] | null
  credentialSecretId: string | null
  createdAt: string
  updatedAt: string
}

export interface ProjectKnowledgeRepoRequest {
  name: string
  repoUrl: string
  branch?: string
  instructionPaths?: string[]
  credentialSecretId?: string | null
}

export const projectKnowledgeReposApi = {
  list: (projectId: string) =>
    api.get<ProjectKnowledgeRepo[]>(`/projects/${projectId}/knowledge-repos`),
  get: (projectId: string, id: string) =>
    api.get<ProjectKnowledgeRepo>(`/projects/${projectId}/knowledge-repos/${id}`),
  create: (projectId: string, data: ProjectKnowledgeRepoRequest) =>
    api.post<ProjectKnowledgeRepo>(`/projects/${projectId}/knowledge-repos`, data),
  update: (projectId: string, id: string, data: ProjectKnowledgeRepoRequest) =>
    api.put<ProjectKnowledgeRepo>(`/projects/${projectId}/knowledge-repos/${id}`, data),
  delete: (projectId: string, id: string) =>
    api.delete<void>(`/projects/${projectId}/knowledge-repos/${id}`),
}

// ============================================================================
// Conversations API (Phase 6)
// ============================================================================

export type ConversationRole =
  | 'USER'
  | 'ASSISTANT'
  | 'SYSTEM'
  | 'TOOL'
  | 'APPROVAL_REQUEST'
  | 'APPROVAL_RESPONSE'
  | 'CONTEXT_SUMMARY'
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'

export interface Conversation {
  id: string
  projectId: string
  agentId: string | null
  agentHostId: string | null
  assistantId: string | null
  assistantVersionId: string | null
  title: string | null
  status: ConversationStatus | null
  systemPromptOverride: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string | null
}

export type MessageFeedbackRating = 'UP' | 'DOWN'

export interface ConversationMessage {
  id: string
  conversationId: string
  sequenceNo: number
  role: ConversationRole
  content: string
  authorUserId: string | null
  authorAgentId: string | null
  modelCode: string | null
  tokenCount: number | null
  toolCallId: string | null
  parentMessageId: string | null
  payloadJson: string | null
  approvalStatus: ApprovalStatus | null
  approverId: string | null
  expiresAt: string | null
  pinned: boolean
  feedbackRating: MessageFeedbackRating | null
  feedbackReason: string | null
  feedbackBy: string | null
  feedbackAt: string | null
  superseded: boolean
  createdAt: string
}

// #30 — a single citation surfaced under an assistant message. Parsed from the
// message's payloadJson (the agent retrieval path persists these); a superset of
// the engine retrieval fields plus the optional knowledgeBaseId the side-panel
// fetch needs to build its URL.
export interface MessageCitation {
  chunkId: string
  sourceId: string
  sourceName: string
  locator: string
  score?: number | null
  knowledgeBaseId?: string | null
}

// #30 — extract the citation list (if any) from an assistant message's
// payloadJson. Returns [] for null/blank/malformed payloads or when no
// well-formed `citations` array is present, so callers never throw on bad data.
export function parseMessageCitations(payloadJson: string | null): MessageCitation[] {
  if (!payloadJson) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(payloadJson)
  } catch {
    return []
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return []
  }
  const raw = (parsed as { citations?: unknown }).citations
  if (!Array.isArray(raw)) {
    return []
  }
  const citations: MessageCitation[] = []
  for (const item of raw) {
    if (typeof item !== 'object' || item === null) {
      continue
    }
    const c = item as Record<string, unknown>
    if (
      typeof c.chunkId === 'string' &&
      typeof c.sourceId === 'string' &&
      typeof c.sourceName === 'string' &&
      typeof c.locator === 'string'
    ) {
      citations.push({
        chunkId: c.chunkId,
        sourceId: c.sourceId,
        sourceName: c.sourceName,
        locator: c.locator,
        score: typeof c.score === 'number' ? c.score : null,
        knowledgeBaseId: typeof c.knowledgeBaseId === 'string' ? c.knowledgeBaseId : null,
      })
    }
  }
  return citations
}

export interface CreateConversationRequest {
  projectId: string
  agentId?: string | null
  assistantId?: string | null
  title?: string | null
  systemPromptOverride?: string | null
}

// One row of the append-only conversation lifecycle log
// (conversation-observability §4): a worker-FSM transition with per-attempt
// worker/host attribution and the reason it happened.
export type ConversationEventReason =
  | 'RESERVED'
  | 'BIND_ACKED'
  | 'BIND_NACKED'
  | 'INSTANCE_BOUND'
  | 'RELEASED'
  | 'RESERVE_TIMEOUT'
  | 'CONNECT_TIMEOUT'
  | 'HOST_LOST'

export interface ConversationEvent {
  id: string
  conversationId: string
  seq: number
  fromState: string | null
  toState: string
  reasonCode: ConversationEventReason
  agentId: string | null
  agentHostId: string | null
  bindAttemptNo: number
  attributes: Record<string, unknown> | null
  occurredAt: string
}

export const conversationsApi = {
  listByProject: (projectId: string) =>
    api.get<Conversation[]>(`/conversations?projectId=${projectId}`),
  get: (id: string) => api.get<Conversation>(`/conversations/${id}`),
  create: (data: CreateConversationRequest) =>
    api.post<Conversation>('/conversations', data),
  /**
   * Owner-only partial update for the chat {@code ⋯} menu — rename and/or
   * archive / unarchive. Both fields are optional; omit one to leave it
   * untouched. {@code status} accepts only ACTIVE or ARCHIVED.
   */
  update: (
    id: string,
    data: { title?: string; status?: ConversationStatus },
  ) => api.patch<Conversation>(`/conversations/${id}`, data),
  messages: (id: string, opts?: { limit?: number; before?: number }) => {
    const params = new URLSearchParams()
    if (opts?.limit != null) params.set('limit', String(opts.limit))
    if (opts?.before != null) params.set('before', String(opts.before))
    const qs = params.toString()
    return api.get<ConversationMessage[]>(
      `/conversations/${id}/messages${qs ? `?${qs}` : ''}`,
    )
  },
  /**
   * Replay the append-only worker-bind lifecycle log for a conversation
   * (reserve / bind / release / timeout transitions), oldest first.
   */
  events: (id: string) =>
    api.get<ConversationEvent[]>(`/conversations/${id}/events`),
  postUserMessage: (id: string, content: string) =>
    api.post<ConversationMessage>(`/conversations/${id}/messages`, { content }),
  /**
   * Cancel the in-flight assistant turn (#4). The engine relays the cancel to
   * the bound worker over the conversation socket; `delivered` is false when
   * no worker is currently streaming (nothing to stop).
   */
  cancelTurn: (id: string) =>
    api.post<{ delivered: boolean }>(`/conversations/${id}/cancel`, {}),
  /**
   * Explicitly summarise the conversation so far (#8) — folds the earlier
   * turns into a running CONTEXT_SUMMARY for handoff to a fresh assistant.
   * `dispatched` is false when a summary is already in flight or there is
   * nothing new to fold.
   */
  summariseNow: (id: string) =>
    api.post<{ dispatched: boolean }>(`/conversations/${id}/summarise`, {}),
  /**
   * Edit a prior USER turn and resend it (#104b), forking the active branch
   * from that message. The replaced rows are soft-superseded (retained for
   * transparency); a fresh USER turn is appended and a new assistant turn is
   * dispatched. Returns the newly appended USER message.
   */
  editAndResend: (conversationId: string, messageId: string, content: string) =>
    api.post<ConversationMessage>(
      `/conversations/${conversationId}/messages/${messageId}/edit`,
      { content },
    ),
  /**
   * Regenerate an ASSISTANT answer (#104b). The assistant turn is
   * soft-superseded and a fresh turn is dispatched against the same preceding
   * USER turn. Returns the superseded assistant message.
   */
  regenerate: (conversationId: string, messageId: string) =>
    api.post<ConversationMessage>(
      `/conversations/${conversationId}/messages/${messageId}/regenerate`,
      {},
    ),
  /**
   * Pin or unpin a single message. Returns the updated message row.
   */
  pinMessage: (conversationId: string, messageId: string, pinned: boolean) =>
    api.patch<ConversationMessage>(
      `/conversations/${conversationId}/messages/${messageId}/pin`,
      { pinned },
    ),
  /**
   * Rate (👍 / 👎) or clear feedback on an ASSISTANT message (#104a).
   * Pass {@code rating: null} to withdraw existing feedback. The optional
   * {@code reason} is a short free-text note. Returns the updated row.
   */
  rateMessage: (
    conversationId: string,
    messageId: string,
    rating: MessageFeedbackRating | null,
    reason?: string | null,
  ) =>
    api.patch<ConversationMessage>(
      `/conversations/${conversationId}/messages/${messageId}/feedback`,
      { rating, reason: reason ?? null },
    ),
  /**
   * Export the conversation transcript as Markdown (#104d). The engine
   * enforces ACL + records an audited export event; returns the rendered
   * document as raw text for client-side download.
   */
  exportMarkdown: (conversationId: string) =>
    api.getText(`/conversations/${conversationId}/export`),
  /**
   * #88 — whether a worker is online to answer this conversation, so the chat
   * UI can warn before sending that a message will be queued. `online` is true
   * when at least one connected (non-DEAD) instance of the pinned host exists;
   * `idleCount` distinguishes "ready now" from "all busy".
   */
  agentAvailability: (conversationId: string) =>
    api.get<AgentAvailability>(
      `/conversations/${conversationId}/agent-availability`,
    ),
  /**
   * Phase 7c — submit a human decision (APPROVED / REJECTED) against a
   * pending APPROVAL_REQUEST row. The engine returns the request row
   * (refreshed with status + approver) plus the new APPROVAL_RESPONSE
   * row so the UI can render both without a follow-up fetch.
   */
  submitApprovalDecision: (
    conversationId: string,
    messageId: string,
    decision: 'APPROVED' | 'REJECTED',
    comment?: string,
  ) =>
    api.post<ConversationMessage[]>(
      `/conversations/${conversationId}/approvals/${messageId}`,
      { decision, comment: comment ?? null },
    ),
}

// #88 — conversation-scoped agent availability for the chat header indicator.
export interface AgentAvailability {
  online: boolean
  connectedCount: number
  idleCount: number
}

// ==================== Attachments (#103) ====================

export type AttachmentScanStatus = 'CLEAN' | 'INFECTED' | 'ERROR'

export interface Attachment {
  id: string
  conversationId: string
  messageId: string | null
  uploadedBy: string | null
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string | null
  scanStatus: AttachmentScanStatus
  scanThreat: string | null
  createdAt: string
}

// #103-C — promote a clean attachment into a project knowledge base.
export interface PromoteAttachmentRequest {
  knowledgeBaseId: string
  sourceName?: string | null
}

export interface PromoteAttachmentResponse {
  knowledgeSourceId: string
  status: string
}

export const attachmentsApi = {
  /** Upload one file to a conversation. Returns the created (scanned) row. */
  upload: (conversationId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.postForm<Attachment>(
      `/conversations/${conversationId}/attachments`,
      form,
    )
  },
  /** All attachments for a conversation, oldest first. */
  list: (conversationId: string) =>
    api.get<Attachment[]>(`/conversations/${conversationId}/attachments`),
  /** Delete an attachment (removes the stored blob + row). */
  delete: (conversationId: string, attachmentId: string) =>
    api.delete<void>(
      `/conversations/${conversationId}/attachments/${attachmentId}`,
    ),
  /** Absolute download path for a clean attachment's bytes. */
  contentPath: (conversationId: string, attachmentId: string) =>
    `${API_BASE}/conversations/${conversationId}/attachments/${attachmentId}/content`,
  /**
   * #103-C — promote a CLEAN attachment into a project knowledge base. The
   * engine extracts/ingests the content as a manual knowledge source and
   * returns the created source id; requires project EDITOR.
   */
  promote: (
    projectId: string,
    conversationId: string,
    attachmentId: string,
    data: PromoteAttachmentRequest,
  ) =>
    api.post<PromoteAttachmentResponse>(
      `/projects/${projectId}/conversations/${conversationId}/attachments/${attachmentId}/promote`,
      data,
    ),
}

// ==================== Audit Log (Phase 9c) ====================

export interface AuditLogEntry {
  id: string
  actorUserId: string | null
  action: string
  resourceType: string | null
  resourceId: string | null
  scopeType: string | null
  scopeId: string | null
  ipAddress: string | null
  userAgent: string | null
  requestId: string | null
  payloadJson: string | null
  createdAt: string
}

export interface AuditLogPage {
  items: AuditLogEntry[]
  totalElements: number
  page: number
  size: number
}

export interface AuditLogQuery {
  actorUserId?: string
  action?: string
  resourceType?: string
  resourceId?: string
  since?: string
  until?: string
  page?: number
  size?: number
}

export const auditLogApi = {
  search: (q: AuditLogQuery = {}) => {
    const params = new URLSearchParams()
    if (q.actorUserId) params.set('actorUserId', q.actorUserId)
    if (q.action) params.set('action', q.action)
    if (q.resourceType) params.set('resourceType', q.resourceType)
    if (q.resourceId) params.set('resourceId', q.resourceId)
    if (q.since) params.set('since', q.since)
    if (q.until) params.set('until', q.until)
    if (q.page !== undefined) params.set('page', String(q.page))
    if (q.size !== undefined) params.set('size', String(q.size))
    const qs = params.toString()
    return api.get<AuditLogPage>(`/audit-log${qs ? `?${qs}` : ''}`)
  },
}

// ==================== Quotas (Phase 8d) ====================

export type QuotaScope = 'ORG' | 'GROUP' | 'PROJECT' | 'USER'
export type QuotaResourceType = 'TOKENS' | 'COST_USD_CENTS'
export type QuotaPeriod = 'DAILY' | 'MONTHLY_CALENDAR' | 'LIFETIME'

export interface Quota {
  id: string
  scopeType: QuotaScope
  scopeId: string
  resourceType: QuotaResourceType
  period: QuotaPeriod
  limitAmount: number
  enforced: boolean
  tags: Record<string, unknown> | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateQuotaRequest {
  scopeType: QuotaScope
  scopeId: string
  resourceType: QuotaResourceType
  period: QuotaPeriod
  limitAmount: number
  enforced: boolean
  tags?: Record<string, unknown> | null
}

export interface UpdateQuotaRequest {
  limitAmount: number
  enforced: boolean
  tags?: Record<string, unknown> | null
}

export interface QuotaConsumption {
  blocked: boolean
  warning: boolean
  limitAmount: number
  consumedAmount: number
  remainingAmount: number
  scopeHit: QuotaScope | null
}

export const quotasApi = {
  list: (scopeType?: QuotaScope, scopeId?: string) => {
    const params = new URLSearchParams()
    if (scopeType) params.set('scopeType', scopeType)
    if (scopeId) params.set('scopeId', scopeId)
    const qs = params.toString()
    return api.get<Quota[]>(`/admin/quotas${qs ? `?${qs}` : ''}`)
  },
  create: (req: CreateQuotaRequest) =>
    api.post<Quota>('/admin/quotas', req),
  update: (id: string, req: UpdateQuotaRequest) =>
    api.put<Quota>(`/admin/quotas/${id}`, req),
  delete: (id: string) =>
    api.delete<void>(`/admin/quotas/${id}`),
  /**
   * Probe how the policy engine would respond to a {@code charge}
   * against {@code scopeId}. Returns consumed / limit / blocked so the
   * UI can render the 80% warning band or 100% red banner without
   * waiting for the next real request to fail.
   */
  consumption: (
    scopeType: QuotaScope,
    scopeId: string,
    resourceType: QuotaResourceType,
    amount = 0,
  ) => {
    const params = new URLSearchParams({
      scopeType,
      scopeId,
      resourceType,
      amount: String(amount),
    })
    return api.get<QuotaConsumption>(`/admin/quotas/consumption?${params.toString()}`)
  },
}

// ---- System Settings (#71a) ----

export type SettingType = 'STRING' | 'INT' | 'RATIO' | 'BOOL' | 'MODEL_REF' | 'JSON'

export interface SystemSetting {
  key: string
  valueType: SettingType
  value: string | null
  description: string | null
  updatedBy: string | null
  updatedAt: string
}

export interface UpdateSystemSettingRequest {
  value: string
}

export const systemSettingsApi = {
  list: () => api.get<SystemSetting[]>('/admin/system-settings'),
  update: (key: string, req: UpdateSystemSettingRequest) =>
    api.put<SystemSetting>(`/admin/system-settings/${encodeURIComponent(key)}`, req),
}

// ==================== My Work (UC-013) ====================

/** Per-tab `(active / created)` counter shared by the Workflows and Conversations tabs. */
export interface MyWorkTabCounter {
  active: number
  created: number
}

/** Counters + first-run / get-started hints for the My Work landing page. */
export interface MyWorkSummary {
  workflows: MyWorkTabCounter
  conversations: MyWorkTabCounter
  approvalsPending: number
  archivedCount: number
  firstRun: boolean
  canCreateWorkflow: boolean
  canCreateConversation: boolean
}

/** One row in the My Work Workflows tab. */
export interface MyWorkflowRow {
  id: string
  projectId: string
  projectName: string | null
  name: string
  description: string | null
  status: WorkflowStatus
  version: number
  activeExecutions: number
  lastExecutionAt: string | null
  lastExecutionStatus: RequestStatus | null
}

/** One row in the My Work Conversations tab (an Assistant with its live session rollup). */
export interface MyAssistantRow {
  id: string
  projectId: string
  projectName: string | null
  name: string
  description: string | null
  status: string
  activeSessions: number
  lastSessionAt: string | null
}

export type MyApprovalSource = 'EXECUTION' | 'CONVERSATION'

/** One pending decision in the My Work Approvals tab. */
export interface MyApprovalRow {
  messageId: string
  conversationId: string
  projectId: string
  projectName: string | null
  source: MyApprovalSource
  assistantId: string | null
  summary: string | null
  payloadJson: string | null
  requestedByUserId: string | null
  externalUserRef: string | null
  requestedAt: string
  expiresAt: string | null
}

export type MyArchivedType = 'WORKFLOW' | 'ASSISTANT'

/** One archived service definition in the My Work Archived tab. */
export interface MyArchivedRow {
  id: string
  projectId: string
  projectName: string | null
  type: MyArchivedType
  name: string
  description: string | null
  archivedAt: string | null
}

/** A chip in the Conversations-tab Continue rail (the caller's own recent session). */
export interface MyContinueRow {
  conversationId: string
  title: string | null
  assistantId: string | null
  assistantName: string | null
  lastMessageAt: string | null
}

interface MyWorkQuery {
  projectIds?: string[]
  status?: WorkflowStatus
  type?: MyArchivedType
  q?: string
  limit?: number
}

function myWorkQueryString(opts?: MyWorkQuery): string {
  const params = new URLSearchParams()
  opts?.projectIds?.forEach((id) => params.append('projectIds', id))
  if (opts?.status) params.set('status', opts.status)
  if (opts?.type) params.set('type', opts.type)
  if (opts?.q) params.set('q', opts.q)
  if (opts?.limit != null) params.set('limit', String(opts.limit))
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export const myWorkApi = {
  summary: (projectIds?: string[]) =>
    api.get<MyWorkSummary>(`/my-work/summary${myWorkQueryString({ projectIds })}`),
  workflows: (opts?: Pick<MyWorkQuery, 'projectIds' | 'status' | 'q'>) =>
    api.get<MyWorkflowRow[]>(`/my-work/workflows${myWorkQueryString(opts)}`),
  conversations: (opts?: Pick<MyWorkQuery, 'projectIds' | 'q'>) =>
    api.get<MyAssistantRow[]>(`/my-work/conversations${myWorkQueryString(opts)}`),
  continueRail: (opts?: Pick<MyWorkQuery, 'projectIds' | 'limit'>) =>
    api.get<MyContinueRow[]>(`/my-work/conversations/continue${myWorkQueryString(opts)}`),
  approvals: (opts?: Pick<MyWorkQuery, 'projectIds' | 'q'>) =>
    api.get<MyApprovalRow[]>(`/my-work/approvals${myWorkQueryString(opts)}`),
  archived: (opts?: Pick<MyWorkQuery, 'projectIds' | 'type' | 'q'>) =>
    api.get<MyArchivedRow[]>(`/my-work/archived${myWorkQueryString(opts)}`),
}
