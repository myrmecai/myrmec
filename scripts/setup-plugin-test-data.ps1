# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 The Myrmec Authors

# Plugin Test Data Setup
# Creates a minimal, repeatable dataset for testing the Myrmec VS Code
# plugin end-to-end: models, profiles, agents, project, workflow,
# assistant, and a plugin test user with full access.
#
# Prerequisites:
#   Engine running with e2e profile: cd control/engine; .\scripts\start-engine-e2e.ps1
#
# Usage:
#   .\scripts\setup-plugin-test-data.ps1

param(
    [string]$BaseUrl = "http://localhost:9090",
    [string]$AdminEmail = "admin@e2e-test.local",
    [string]$AdminPassword = "E2eTest@123!",
    [string]$PluginUserEmail = "joga.singh@myrmec.ai",
    [string]$PluginUserPassword = "joga123!",
    [string]$PluginUserName = "Joga Singh",
    [string]$OutputFile = "..\agent-registration.env"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputPath = Join-Path $ScriptDir $OutputFile

$ApiUrl = "$BaseUrl/api/v1"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "     Myrmec Plugin Test Data Setup" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [switch]$IgnoreError
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers + @{ "Content-Type" = "application/json" }
    }

    if ($Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }

    try {
        return Invoke-RestMethod @params
    } catch {
        if ($IgnoreError) {
            return $null
        }
        $statusCode = $_.Exception.Response.StatusCode.value__
        $statusDesc = $_.Exception.Response.StatusDescription
        Write-Host ""
        Write-Host "   ERROR: $statusCode $statusDesc" -ForegroundColor Red
        try {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $reader.BaseStream.Position = 0
            $errorBody = $reader.ReadToEnd()
            Write-Host "   Response: $errorBody" -ForegroundColor Red
        } catch {}
        throw
    }
}

# ============================================================
# STEP 1: Login as Admin
# ============================================================
Write-Host "1. Authenticating as admin..." -ForegroundColor Yellow

$loginResponse = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body @{
    email = $AdminEmail
    password = $AdminPassword
}
$adminToken = $loginResponse.accessToken
$adminUserId = $loginResponse.userId
$adminHeaders = @{ "Authorization" = "Bearer $adminToken" }

Write-Host "   Authenticated as: $AdminEmail (ID: $adminUserId)" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 2: Create Plugin Test User
# ============================================================
Write-Host "2. Creating plugin test user ($PluginUserEmail)..." -ForegroundColor Yellow

$pluginUser = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users" -Headers $adminHeaders -Body @{
    email = $PluginUserEmail
    name = $PluginUserName
    providerCode = "LOCAL"
    password = $PluginUserPassword
} -IgnoreError

$pluginUserId = if ($pluginUser) { $pluginUser.id } else { $null }
if (-not $pluginUserId) {
    $existingUsers = Invoke-Api -Method GET -Uri "$ApiUrl/admin/users" -Headers $adminHeaders
    if ($existingUsers -and $existingUsers -isnot [Array]) { $existingUsers = @($existingUsers) }
    $existing = $existingUsers | Where-Object { $_.email -eq $PluginUserEmail } | Select-Object -First 1
    if ($existing) {
        $pluginUserId = $existing.id
        Write-Host "   User already exists (ID: $pluginUserId)" -ForegroundColor Yellow
    } else {
        throw "Could not create or find plugin test user"
    }
} else {
    Write-Host "   User created (ID: $pluginUserId)" -ForegroundColor Green
}

# System EDITOR so the user can create content anywhere, plus VIEWER via expansion.
$roleResult = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users/$pluginUserId/roles" -Headers $adminHeaders -Body @{
    role = "EDITOR"
    scopeType = "SYSTEM"
} -IgnoreError
if ($roleResult) {
    Write-Host "   Assigned system EDITOR role" -ForegroundColor Green
}

# ============================================================
# STEP 3: Create Models (matching myrmec-ee/e2e/addressbook-workflow.yaml)
# ============================================================
Write-Host "3. Creating models..." -ForegroundColor Yellow

$models = @(
    @{
        code = "glm-5-3"
        name = "GLM 5.3"
        provider = "ollama"
        modelId = "glm-5.3:cloud"
        apiEndpoint = "http://localhost:11434/v1"
    },
    @{
        code = "kimi-k2-7-code"
        name = "Kimi K2.7 Code"
        provider = "ollama"
        modelId = "kimi-k2.7-code:cloud"
        apiEndpoint = "http://localhost:11434/v1"
    },
    @{
        code = "deepseek-v4-pro"
        name = "DeepSeek V4 Pro"
        provider = "ollama"
        modelId = "deepseek-v4-pro:cloud:cloud"
        apiEndpoint = "http://localhost:11434/v1"
    }
)

$modelIds = @{}
foreach ($model in $models) {
    $created = Invoke-Api -Method POST -Uri "$ApiUrl/admin/models" -Headers $adminHeaders -Body $model -IgnoreError
    if ($created) {
        $modelIds[$model.code] = $created.id
        Write-Host "   Created model: $($model.name)" -ForegroundColor Green
    } else {
        $existingModels = Invoke-Api -Method GET -Uri "$ApiUrl/admin/models" -Headers $adminHeaders
        if ($existingModels -and $existingModels -isnot [Array]) { $existingModels = @($existingModels) }
        $existing = $existingModels | Where-Object { $_.code -eq $model.code } | Select-Object -First 1
        if ($existing) {
            $modelIds[$model.code] = $existing.id
            Write-Host "   Model exists: $($model.name)" -ForegroundColor Yellow
        }
    }
}

# ------------------------------------------------------------
# STEP 3b: Ensure execute_command tool exists (used by orchestration)
# ------------------------------------------------------------
Write-Host "3b. Ensuring execute_command tool exists..." -ForegroundColor Yellow

$execTool = Invoke-Api -Method POST -Uri "$ApiUrl/admin/tools" -Headers $adminHeaders -Body @{
    code = "execute_command"
    name = "Execute Command"
    description = "Execute a shell command in the workspace"
    toolType = "SYSTEM"
    configSchema = @{
        command = @{ type = "string"; description = "Command to execute" }
        workingDirectory = @{ type = "string"; description = "Working directory" }
    }
} -IgnoreError
if ($execTool) {
    Write-Host "   Created execute_command tool" -ForegroundColor Green
} else {
    Write-Host "   execute_command tool already exists or could not be created" -ForegroundColor Yellow
}

# ============================================================
# STEP 4: Create Agent Profiles
# ============================================================
Write-Host "4. Creating agent profiles..." -ForegroundColor Yellow

$profiles = @(
    @{
        name = "governed-coding"
        description = "Governed coding profile for backend, frontend, and e2e orchestration"
        capabilities = @("java:21", "maven", "spring-boot:3", "react:18", "typescript:5", "nodejs:20", "playwright")
        toolCodes = @("read_file", "write_file", "list_directory", "create_directory", "search_files", "search_code", "delete_file", "execute_command", "git_status", "git_diff", "git_add", "git_commit", "git_push", "git_log", "git_branch", "git_merge")
        systemPrompt = @"
You are a governed full-stack developer. Use the provided tools to implement, review, and test changes.
- Use read_file, list_directory, and search_files to explore.
- Use write_file to create or update files with complete content.
- Use execute_command to run tests and builds.
- After writing files, use git_add, git_commit, and git_push.
- Respond with a brief summary, NOT code blocks.
"@
        defaultModel = "kimi-k2-7-code"
        commandTemplates = @{
            mvnw = @{
                executable = "./mvnw"
                args = @("clean", "test")
                timeoutSeconds = 600
                riskClass = "SAFE"
                maxOutputBytes = 1048576
            }
            "npm-test" = @{
                executable = "npm"
                args = @("test")
                timeoutSeconds = 600
                riskClass = "SAFE"
                maxOutputBytes = 1048576
            }
            "playwright-test" = @{
                executable = "npx"
                args = @("playwright", "test")
                timeoutSeconds = 600
                riskClass = "SAFE"
                maxOutputBytes = 1048576
            }
        }
    },
    @{
        name = "Chat Assistant"
        description = "General-purpose conversational assistant for interactive chat sessions"
        capabilities = @("conversational")
        # Workspace file tools so the local agent can inspect the user's open
        # VS Code folder. Read-only by design for this first slice — add
        # write_file / create_directory once read access is verified.
        # Deliberately excludes execute_command and the git_* tools.
        toolCodes = @("read_file", "list_directory")
        systemPrompt = @"
You are a helpful, knowledgeable AI assistant working inside the user's editor.

You have tools to read the user's workspace:
- list_directory: list files/folders (use "." for the workspace root)
- read_file: read a text file (path relative to the workspace root)

When the user asks about their project or files, use these tools to inspect
the workspace before answering, and say which file you read. If a tool reports
that a path is outside the workspace, explain that you can only read inside the
open project folder.

Answer questions clearly and concisely.
"@
        defaultModel = "glm-5-3"
    }
)

$profileIds = @{}
foreach ($profile in $profiles) {
    $created = Invoke-Api -Method POST -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders -Body $profile -IgnoreError
    if ($created) {
        $profileIds[$profile.name] = $created.id
        Write-Host "   Created profile: $($profile.name)" -ForegroundColor Green
    } else {
        $existingProfiles = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders
        if ($existingProfiles -and $existingProfiles -isnot [Array]) { $existingProfiles = @($existingProfiles) }
        $existing = $existingProfiles | Where-Object { $_.name -eq $profile.name } | Select-Object -First 1
        if ($existing) {
            $profileIds[$profile.name] = $existing.id
            Write-Host "   Profile exists: $($profile.name)" -ForegroundColor Yellow

            # Zone 2 fields (tools, prompt, model) live on the published
            # version. Re-running the script must reconcile them, otherwise an
            # older profile keeps its stale tool list and the plugin sees no
            # tools. updateProfile runs the engine's draft -> publish cycle
            # internally and is a no-op when the content already matches.
            $desiredToolCodes = @($profile.toolCodes) | Sort-Object
            $actualToolCodes = @($existing.toolCodes) | Sort-Object
            $toolsMatch = (($desiredToolCodes -join ',') -eq ($actualToolCodes -join ','))
            $promptMatches = ($existing.systemPrompt -eq $profile.systemPrompt)
            $modelMatches = ($existing.defaultModel -eq $profile.defaultModel)

            if (-not ($toolsMatch -and $promptMatches -and $modelMatches)) {
                $updateBody = @{
                    name          = $profile.name
                    description   = $profile.description
                    capabilities  = $profile.capabilities
                    toolCodes     = @($profile.toolCodes)
                    systemPrompt  = $profile.systemPrompt
                    defaultModel  = $profile.defaultModel
                }
                $updated = Invoke-Api -Method PUT -Uri "$ApiUrl/admin/agent-profiles/$($existing.id)" -Headers $adminHeaders -Body $updateBody -IgnoreError
                if ($updated) {
                    $newTools = @($updated.toolCodes) | Sort-Object
                    Write-Host "   Updated profile: $($profile.name) (tools: $($newTools -join ', '))" -ForegroundColor Green
                } else {
                    Write-Host "   WARNING: could not update profile: $($profile.name)" -ForegroundColor Yellow
                }
            }
        }
    }
}

# ============================================================
# STEP 5: Create Agents
# ============================================================
Write-Host "5. Creating agents..." -ForegroundColor Yellow

function Create-OrFetchAgent {
    param(
        [hashtable]$AgentRequest,
        [hashtable]$AgentIds
    )
    $name = $AgentRequest.name
    $key = $null

    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/agent-hosts" -Headers $adminHeaders -Body $AgentRequest -IgnoreError
    if ($result -and $result.agent) {
        $AgentIds[$name] = $result.agent.id
        $key = $result.registrationKey
        Write-Host "   Created agent: $name" -ForegroundColor Green
    } else {
        $existingAgents = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agent-hosts" -Headers $adminHeaders
        if ($existingAgents -and $existingAgents -isnot [Array]) { $existingAgents = @($existingAgents) }
        $existing = $existingAgents | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($existing) {
            $AgentIds[$name] = $existing.id
            Write-Host "   Agent exists: $name" -ForegroundColor Yellow
            if ($existing.profileId -ne $AgentRequest.profileId) {
                Invoke-Api -Method PUT -Uri "$ApiUrl/admin/agent-hosts/$($existing.id)" -Headers $adminHeaders -Body @{
                    profileId = $AgentRequest.profileId
                } -IgnoreError | Out-Null
                Write-Host "   Updated agent profile to governed-coding" -ForegroundColor Green
            }
            try {
                $regKeyResponse = Invoke-Api -Method POST -Uri "$ApiUrl/admin/agent-hosts/$($existing.id)/regenerate-key" -Headers $adminHeaders
                if ($regKeyResponse -and $regKeyResponse.registrationKey) {
                    $key = $regKeyResponse.registrationKey
                }
            } catch {
                Write-Host "   Could not regenerate key for $name" -ForegroundColor Yellow
            }
        }
    }
    return $key
}

$agentIds = @{}
$regKey = Create-OrFetchAgent -AgentRequest @{
    name = "Addressbook Fullstack Agent"
    description = "Full-stack agent for the Address Book application"
    profileId = $profileIds["governed-coding"]
    maxAgents = 5
} -AgentIds $agentIds

$chatRegKey = Create-OrFetchAgent -AgentRequest @{
    name = "Chat Assistant Agent"
    description = "General-purpose conversational assistant"
    profileId = $profileIds["Chat Assistant"]
    maxAgents = 3
} -AgentIds $agentIds

if (-not $regKey) {
    throw "Failed to get registration key for Addressbook Fullstack Agent"
}

# ============================================================
# STEP 6: Save Registration Keys
# ============================================================
Write-Host "6. Saving registration keys..." -ForegroundColor Yellow

$envContent = @"
# Myrmec Agent Registration Key
# Created: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
# Agent: Addressbook Fullstack Agent

MYRMEC_REGISTRATION_KEY=$regKey
MYRMEC_ENGINE_URL=$BaseUrl
MYRMEC_AGENT_NAME=plugin-test-agent
MYRMEC_HEARTBEAT_INTERVAL=30
"@
$envContent | Out-File -FilePath $OutputPath -Encoding UTF8
Write-Host "   Fullstack agent key saved to: $OutputPath" -ForegroundColor Green

if ($chatRegKey) {
    $chatEnvPath = Join-Path $ScriptDir "..\chat-agent-registration.env"
    $chatEnvContent = @"
# Myrmec Chat Agent Registration Key
# Created: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
# Agent: Chat Assistant Agent

MYRMEC_REGISTRATION_KEY=$chatRegKey
MYRMEC_ENGINE_URL=$BaseUrl
MYRMEC_AGENT_NAME=plugin-test-chat-agent
"@
    $chatEnvContent | Out-File -FilePath $chatEnvPath -Encoding UTF8
    Write-Host "   Chat agent key saved to: $chatEnvPath" -ForegroundColor Green
}

# ============================================================
# STEP 7: Create Project
# ============================================================
Write-Host "7. Creating project..." -ForegroundColor Yellow

$projectBody = @{
    name = "Address Book Application"
    description = "Contact management application for plugin testing"
    workspaceRepoUrl = "https://github.com/myrmec-org/test-data.git"
    workspaceRepoBranch = "main"
}

$project = Invoke-Api -Method POST -Uri "$ApiUrl/projects" -Headers $adminHeaders -Body $projectBody -IgnoreError
if (-not $project -or -not $project.id) {
    $existingProjects = Invoke-Api -Method GET -Uri "$ApiUrl/projects" -Headers $adminHeaders
    if ($existingProjects -and $existingProjects -isnot [Array]) { $existingProjects = @($existingProjects) }
    $project = $existingProjects | Where-Object { $_.name -eq $projectBody.name } | Select-Object -First 1
}

if (-not $project -or -not $project.id) {
    throw "Could not create or find project"
}

# Ensure the project workspace repository matches the addressbook-workflow.yaml source.
if ($project.workspaceRepoUrl -ne $projectBody.workspaceRepoUrl -or $project.workspaceRepoBranch -ne $projectBody.workspaceRepoBranch) {
    # Project updates require project-scoped EDITOR/OWNER; use the plugin user's token.
    if (-not $pluginToken) {
        $pluginLogin = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body @{
            email = $PluginUserEmail
            password = $PluginUserPassword
        }
        $pluginToken = $pluginLogin.accessToken
        $pluginHeaders = @{ "Authorization" = "Bearer $pluginToken" }
    }
    Invoke-Api -Method PUT -Uri "$ApiUrl/projects/$($project.id)" -Headers $pluginHeaders -Body @{
        workspaceRepoUrl = $projectBody.workspaceRepoUrl
        workspaceRepoBranch = $projectBody.workspaceRepoBranch
    } -IgnoreError | Out-Null
    Write-Host "   Updated project workspace repository" -ForegroundColor Green
}

$projectId = $project.id
Write-Host "   Project ID: $projectId" -ForegroundColor Green

# Assign PROJECT_OWNER to the plugin user on this project.
$projectOwnerRole = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users/$pluginUserId/roles" -Headers $adminHeaders -Body @{
    role = "PROJECT_OWNER"
    scopeType = "PROJECT"
    projectId = $projectId
} -IgnoreError
if ($projectOwnerRole) {
    Write-Host "   Assigned PROJECT_OWNER on project to $PluginUserEmail" -ForegroundColor Green
}

# ============================================================
# STEP 8: Create AddressBook Workflow
# ============================================================
Write-Host "8. Creating AddressBook workflow..." -ForegroundColor Yellow

# Workflow creation requires project EDITOR/OWNER. Log in as the plugin user.
$pluginLogin = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body @{
    email = $PluginUserEmail
    password = $PluginUserPassword
}
$pluginToken = $pluginLogin.accessToken
$pluginHeaders = @{ "Authorization" = "Bearer $pluginToken" }

$fullstackProfileId = $profileIds["governed-coding"]
$workflowBody = @{
    projectId = $projectId
    name = "AddressBook Orchestration Workflow"
    description = "End-to-end orchestration workflow for Address Book feature implementation"
    steps = @(
        @{
            id = "backend-orchestration"
            name = "Backend Implementation Phase"
            taskType = "ORCHESTRATOR"
            agentProfileId = $fullstackProfileId
            prompt = "Build a working Spring Boot REST API for address-book based on requirements."
            dependsOn = @()
            transitions = @{ default = "frontend-orchestration" }
            retryPolicy = @{ maxRetries = 2; initialBackoffSeconds = 2; maxBackoffSeconds = 30 }
            orchestration = @{
                modelCode = "glm-5-3"
                agentProfileCode = "governed-coding"
                goal = "Build a working Spring Boot REST API for address-book based on requirements."
                specPath = "addressbook/docs/requirements.md"
                sourceSubPath = "addressbook/backend"
                workers = @(
                    @{ name = "coder"; modelCode = "kimi-k2-7-code"; capability = "Writes Java, Spring Boot, JPA, and Maven configuration"; allowedTools = @("read_file", "write_file", "list_directory"); allowedCommands = @("mvnw") }
                    @{ name = "code-reviewer"; modelCode = "deepseek-v4-pro"; capability = "Reviews generated code against security, quality, and requirement specs"; allowedTools = @("read_file", "list_directory"); allowedCommands = @() }
                    @{ name = "test-runner"; modelCode = "kimi-k2-7-code"; capability = "Executes unit and integration tests, reports failures and stack traces"; allowedTools = @("execute_command", "read_file"); allowedCommands = @("mvnw") }
                )
                checkpointStrategy = @{ mode = "ON_VERIFICATION_PASS"; commitMessage = "feat(backend): implement address-book API"; pushToRemote = $false; allowNoChanges = $false }
                completionCriteria = @{ definitionOfDone = "All CRUD API endpoints implemented, code reviewed with zero blocking issues, and all Maven tests pass green."; requireVerificationBy = @("code-reviewer", "test-runner") }
                budget = @{ maxTokens = 300000; maxWorkerCalls = 25; maxVerifierRejectionsPerAttempt = 3; maxOrchestratorIterations = 50; maxWorkerIterations = 25; onBudgetExceeded = "PAUSE_FOR_HUMAN_REVIEW" }
            }
        },
        @{
            id = "frontend-orchestration"
            name = "Frontend Implementation Phase"
            taskType = "ORCHESTRATOR"
            agentProfileId = $fullstackProfileId
            prompt = "Implement React UI components for managing contacts."
            dependsOn = @("backend-orchestration")
            transitions = @{ default = "e2e-orchestration" }
            retryPolicy = @{ maxRetries = 2; initialBackoffSeconds = 2; maxBackoffSeconds = 30 }
            orchestration = @{
                modelCode = "glm-5-3"
                agentProfileCode = "governed-coding"
                goal = "Implement React UI components for managing contacts."
                specPath = "addressbook/docs/ui-requirements.md"
                sourceSubPath = "addressbook/ui"
                workers = @(
                    @{ name = "fe-coder"; modelCode = "kimi-k2-7-code"; capability = "Writes React components, hooks, services, and CSS"; allowedTools = @("read_file", "write_file"); allowedCommands = @("npm-test") }
                    @{ name = "ui-reviewer"; modelCode = "deepseek-v4-pro"; capability = "Verifies component structure and accessibility standards"; allowedTools = @("read_file"); allowedCommands = @() }
                    @{ name = "ui-test-runner"; modelCode = "kimi-k2-7-code"; capability = "Executes React unit and integration tests (Vitest/Testing Library), reports failures"; allowedTools = @("execute_command", "read_file"); allowedCommands = @("npm-test") }
                )
                checkpointStrategy = @{ mode = "ON_VERIFICATION_PASS"; commitMessage = "feat(frontend): implement address-book UI"; pushToRemote = $false; allowNoChanges = $false }
                completionCriteria = @{ definitionOfDone = "UI components render contact lists, form validation works, and React unit tests pass."; requireVerificationBy = @("ui-reviewer", "ui-test-runner") }
                budget = @{ maxTokens = 250000; maxWorkerCalls = 20; maxVerifierRejectionsPerAttempt = 3; maxOrchestratorIterations = 50; maxWorkerIterations = 25; onBudgetExceeded = "PAUSE_FOR_HUMAN_REVIEW" }
            }
        },
        @{
            id = "e2e-orchestration"
            name = "End-to-End Validation Phase"
            taskType = "ORCHESTRATOR"
            agentProfileId = $fullstackProfileId
            prompt = "Write Playwright tests and run end-to-end suite against frontend and backend."
            dependsOn = @("frontend-orchestration")
            transitions = @{}
            retryPolicy = @{ maxRetries = 1; initialBackoffSeconds = 2; maxBackoffSeconds = 15 }
            orchestration = @{
                modelCode = "glm-5-3"
                agentProfileCode = "governed-coding"
                goal = "Write Playwright tests and run end-to-end suite against frontend and backend."
                specPath = $null
                sourceSubPath = "addressbook"
                workers = @(
                    @{ name = "e2e-tester"; modelCode = "kimi-k2-7-code"; capability = "Writes and executes Playwright integration tests"; allowedTools = @("read_file", "write_file", "execute_command"); allowedCommands = @("playwright-test") }
                    @{ name = "e2e-runner"; modelCode = "kimi-k2-7-code"; capability = "Executes Playwright end-to-end scenarios against the integrated stack"; allowedTools = @("execute_command", "read_file"); allowedCommands = @("playwright-test") }
                )
                checkpointStrategy = @{ mode = "ON_VERIFICATION_PASS"; commitMessage = "test(e2e): add address-book scenarios"; pushToRemote = $false; allowNoChanges = $false }
                completionCriteria = @{ definitionOfDone = "All Playwright user scenarios execute green against integrated stack."; requireVerificationBy = @("e2e-runner") }
                budget = @{ maxTokens = 150000; maxWorkerCalls = 10; maxVerifierRejectionsPerAttempt = 2; maxOrchestratorIterations = 50; maxWorkerIterations = 25; onBudgetExceeded = "PAUSE_FOR_HUMAN_REVIEW" }
            }
        }
    )
    inputSchema = @{
        type = "object"
        properties = @{
            featureName = @{ type = "string"; description = "Name of the feature" }
            requirements = @{ type = "string"; description = "Detailed requirements" }
        }
        required = @("featureName", "requirements")
    }
}

$existingWorkflows = Invoke-Api -Method GET -Uri "$ApiUrl/projects/$projectId/workflows" -Headers $pluginHeaders -IgnoreError
if ($existingWorkflows -and $existingWorkflows -isnot [Array]) { $existingWorkflows = @($existingWorkflows) }
$oldWorkflow = $existingWorkflows | Where-Object { $_.name -eq "AddressBook Orchestration Workflow" } | Select-Object -First 1
if ($oldWorkflow -and $oldWorkflow.id) {
    Invoke-Api -Method DELETE -Uri "$ApiUrl/projects/$projectId/workflows/$($oldWorkflow.id)" -Headers $pluginHeaders -IgnoreError | Out-Null
    Write-Host "   Deleted existing AddressBook workflow" -ForegroundColor Yellow
}

$workflow = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows" -Headers $pluginHeaders -Body $workflowBody -IgnoreError
if (-not $workflow -or -not $workflow.id) {
    throw "Could not create workflow"
}

$workflowId = $workflow.id
Write-Host "   Workflow ID: $workflowId" -ForegroundColor Green

# Bind the orchestration alias to the governed-coding profile before publishing.
Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows/$workflowId/orchestration-bindings" -Headers $pluginHeaders -Body @{
    "governed-coding" = $fullstackProfileId
} -IgnoreError | Out-Null
Write-Host "   Orchestration bindings set" -ForegroundColor Green

Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows/$workflowId/publish" -Headers $pluginHeaders -IgnoreError | Out-Null
Write-Host "   Workflow published" -ForegroundColor Green

# ============================================================
# STEP 9: Create and Publish Assistant
# ============================================================
Write-Host "9. Creating AddressBook Assistant..." -ForegroundColor Yellow

$chatProfileId = $profileIds["Chat Assistant"]
$assistantBody = @{
    projectId = $projectId
    name = "AddressBook Assistant"
    description = "General-purpose assistant for Address Book project conversations"
    agentProfileId = $chatProfileId
}

$assistant = Invoke-Api -Method POST -Uri "$ApiUrl/assistants" -Headers $pluginHeaders -Body $assistantBody -IgnoreError
if (-not $assistant -or -not $assistant.id) {
    $existingAssistants = Invoke-Api -Method GET -Uri "$ApiUrl/assistants?projectId=$projectId" -Headers $pluginHeaders -IgnoreError
    if ($existingAssistants -and $existingAssistants -isnot [Array]) { $existingAssistants = @($existingAssistants) }
    $assistant = $existingAssistants | Where-Object { $_.name -eq "AddressBook Assistant" } | Select-Object -First 1
}
if (-not $assistant -or -not $assistant.id) {
    throw "Could not create or find assistant"
}

$assistantId = $assistant.id
Write-Host "   Assistant ID: $assistantId" -ForegroundColor Green

Invoke-Api -Method POST -Uri "$ApiUrl/assistants/$assistantId/publish" -Headers $pluginHeaders -IgnoreError | Out-Null
Write-Host "   Assistant published" -ForegroundColor Green

# ============================================================
# Summary
# ============================================================
Write-Host ""
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "     Plugin Test Data Setup Complete" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "User:" -ForegroundColor Yellow
Write-Host "  $PluginUserEmail / $PluginUserPassword" -ForegroundColor Green
Write-Host ""
Write-Host "IDs:" -ForegroundColor Yellow
Write-Host "  Project:   $projectId" -ForegroundColor Green
Write-Host "  Workflow:  $workflowId" -ForegroundColor Green
Write-Host "  Assistant: $assistantId" -ForegroundColor Green
Write-Host ""
Write-Host "Files:" -ForegroundColor Yellow
Write-Host "  $OutputPath" -ForegroundColor Green
if ($chatRegKey) {
    Write-Host "  $chatEnvPath" -ForegroundColor Green
}
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  1. Start agent(s): .\scripts\start-agent.ps1 (and .\scripts\start-chat-agent.ps1)" -ForegroundColor White
Write-Host "  2. Open VS Code plugin and sign in as $PluginUserEmail" -ForegroundColor White
Write-Host "  3. My Work shows the Address Book orchestration workflow and assistant" -ForegroundColor White
