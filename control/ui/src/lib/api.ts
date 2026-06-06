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
      throw new ApiRequestError(response.status, error)
    }

    if (response.status === 204) {
      return undefined as T
    }

    return response.json()
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path)
  }

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
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
    public error: ApiError
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
  workspaceRepoUrl?: string
  workspaceRepoBranch?: string
  workspaceCredentialSecretId?: string | null
  ragConfig?: RagConfig
}

export interface UpdateProjectRequest {
  name?: string
  description?: string
  status?: ProjectStatus
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
  infraConfig?: Record<string, unknown>
  defaultParams?: Record<string, unknown>
}

export interface UpdateModelRequest {
  name?: string
  apiEndpoint?: string
  apiKey?: string
  requiresAuth?: boolean
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
  maxInstances: number
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
  maxInstances?: number
}

export interface UpdateAgentRequest {
  name?: string
  description?: string
  profileId?: string
  projectId?: string
  modelOverride?: string
  config?: Record<string, unknown>
  maxInstances?: number
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
