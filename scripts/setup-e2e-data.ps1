# Complete E2E Data Setup Script
# Creates all required test data for full E2E testing
# 
# Prerequisites:
#   - Engine running with e2e profile: cd control/engine/engine-core; mvn spring-boot:run -D"spring-boot.run.profiles=e2e"
#
# Usage:
#   .\setup-e2e-data.ps1
#
# Creates:
#   - Tools (file-system, web-browser, code-interpreter, github-api, postgresql)
#   - Models (gpt-4-turbo, gpt-4o, claude-3-sonnet, ollama-llama3, ollama-qwen3-coder-next, ollama-llama3.2)
#   - Agent Profiles (Fullstack - Spring/Maven/React, Chat Assistant)
#   - Agents (Addressbook Fullstack Agent, Chat Assistant Agent)
#   - Registration Keys (agent-registration.env, chat-agent-registration.env)
#   - Project with repo config
#   - Workflow with steps
#   - Organization-level knowledge documents
#   - Project-level knowledge documents
#   - Sample conversations (workflow kickoff + chat-ready)
#   - Published Assistant (Chat Assistant)
#   - Test users (joga.singh@gmail.com, tester@e2e-test.local)

param(
    [string]$BaseUrl = "http://localhost:9090",
    [string]$AdminEmail = "admin@e2e-test.local",
    [string]$AdminPassword = "E2eTest@123!",
    [string]$OutputFile = "..\agent-registration.env"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputPath = Join-Path $ScriptDir $OutputFile
$EnvFile = Join-Path $ScriptDir ".env"

# Load environment variables from .env file if it exists
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            Set-Item -Path "env:$name" -Value $value
        }
    }
    Write-Host "Loaded environment from: $EnvFile" -ForegroundColor Gray
}

$ApiUrl = "$BaseUrl/api/v1"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "     Myrmec E2E Data Setup" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""

# Helper function for API calls
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
            Write-Host "   (skipped - may already exist)" -ForegroundColor Gray
            return $null
        }
        # Show error details
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

$loginBody = @{
    email = $AdminEmail
    password = $AdminPassword
}

$loginResponse = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body $loginBody
$adminToken = $loginResponse.accessToken
$adminUserId = $loginResponse.userId
$adminHeaders = @{ "Authorization" = "Bearer $adminToken" }

Write-Host "   Authenticated as: $AdminEmail (ID: $adminUserId)" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 1b: Create Test Users
# ============================================================
Write-Host "1b. Creating test users..." -ForegroundColor Yellow

$testUsers = @(
    @{
        email = "joga.singh@gmail.com"
        name = "Joga Singh"
        providerCode = "GITHUB_DEFAULT"
        role = "VIEWER"
    },
    @{
        email = "tester@e2e-test.local"
        name = "E2E Tester"
        providerCode = "LOCAL"
        role = "EDITOR"
        password = "E2eTest@123!"
    }
)

foreach ($u in $testUsers) {
    # External auth providers are seeded with is_enabled=false. Enable the provider
    # before creating users that use it, otherwise POST /admin/users returns 400
    # ("Authentication provider is disabled").
    if ($u.providerCode -ne "LOCAL") {
        Write-Host "   Enabling auth provider: $($u.providerCode)..." -NoNewline
        $enableResult = Invoke-Api -Method POST -Uri "$ApiUrl/admin/auth-providers/$($u.providerCode)/enable" -Headers $adminHeaders -IgnoreError
        if ($enableResult) {
            Write-Host " enabled" -ForegroundColor Green
        }
    }

    Write-Host "   Creating user: $($u.email) ($($u.providerCode))..." -NoNewline
    $createBody = @{
        email = $u.email
        name = $u.name
        providerCode = $u.providerCode
    }
    if ($u.password) {
        $createBody.password = $u.password
    }
    $created = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users" -Headers $adminHeaders -Body $createBody -IgnoreError
    $userId = $null
    if ($created) {
        $userId = $created.id
        Write-Host " created (ID: $userId)" -ForegroundColor Green
    } else {
        Write-Host "" # newline after "(skipped)"
        Write-Host "   Looking up existing user..." -NoNewline
        $existingUsers = Invoke-Api -Method GET -Uri "$ApiUrl/admin/users" -Headers $adminHeaders
        if ($existingUsers -and $existingUsers -isnot [Array]) { $existingUsers = @($existingUsers) }
        $existing = $existingUsers | Where-Object { $_.email -eq $u.email }
        if ($existing) {
            $userId = $existing.id
            Write-Host " found (ID: $userId)" -ForegroundColor Yellow
        } else {
            Write-Host " not found" -ForegroundColor Red
        }
    }

    # Track the local tester user for assistant creation (needs EDITOR on project)
    if ($u.email -eq "tester@e2e-test.local" -and $userId) {
        $script:testerUserId = $userId
    }

    if ($userId) {
        # Check whether the role is already assigned (system-wide) to avoid duplicate-409 noise.
        $hasRole = $false
        if ($existing -and $existing.roles) {
            $hasRole = ($existing.roles | Where-Object { $_.role -eq $u.role -and -not $_.projectId }).Count -gt 0
        }
        if (-not $hasRole) {
            Write-Host "   Assigning role $($u.role) to $($u.email)..." -NoNewline
            $roleBody = @{ role = $u.role; scopeType = "SYSTEM" }
            $roleResult = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users/$userId/roles" -Headers $adminHeaders -Body $roleBody -IgnoreError
            if ($roleResult) {
                Write-Host " assigned" -ForegroundColor Green
            }
        } else {
            Write-Host "   Role $($u.role) already assigned to $($u.email)" -ForegroundColor Gray
        }
    }
}
Write-Host ""
Write-Host ""

# ============================================================
# STEP 2: Create Tools
# ============================================================
Write-Host "2. Creating tools..." -ForegroundColor Yellow

$tools = @(
    @{
        code = "file-system"
        name = "File System"
        description = "Read, write, and manage files and directories"
        toolType = "SYSTEM"
        docsUrl = "https://docs.myrmec.ai/tools/file-system"
    },
    @{
        code = "web-browser"
        name = "Web Browser"
        description = "Fetch and parse web pages, execute JavaScript"
        toolType = "SYSTEM"
        docsUrl = "https://docs.myrmec.ai/tools/web-browser"
    },
    @{
        code = "code-interpreter"
        name = "Code Interpreter"
        description = "Execute Python code in a sandboxed environment"
        toolType = "SYSTEM"
        docsUrl = "https://docs.myrmec.ai/tools/code-interpreter"
    },
    @{
        code = "github-api"
        name = "GitHub API"
        description = "Interact with GitHub repositories, issues, and pull requests"
        toolType = "INTEGRATION"
        docsUrl = "https://docs.github.com/en/rest"
    },
    @{
        code = "postgresql"
        name = "PostgreSQL"
        description = "Query and modify PostgreSQL databases"
        toolType = "DATABASE"
        docsUrl = "https://www.postgresql.org/docs/"
    }
)

foreach ($tool in $tools) {
    Write-Host "   Creating tool: $($tool.name)..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/tools" -Headers $adminHeaders -Body $tool -IgnoreError
    if ($result) {
        Write-Host " created" -ForegroundColor Green
    }
}
Write-Host ""

# ============================================================
# STEP 3: Create Models
# ============================================================
Write-Host "3. Creating models..." -ForegroundColor Yellow

# Note: Model providers (openai, anthropic, ollama) are seeded by Liquibase

$models = @(
    @{
        code = "gpt-4-turbo"
        name = "GPT-4 Turbo"
        provider = "openai"
        deploymentType = "CLOUD"
        modelId = "gpt-4-turbo"
        apiKey = "sk-test-key-for-e2e"
    },
    @{
        code = "gpt-4o"
        name = "GPT-4o"
        provider = "openai"
        deploymentType = "CLOUD"
        modelId = "gpt-4o"
        apiKey = "sk-test-key-for-e2e"
    },
    @{
        code = "claude-3-sonnet"
        name = "Claude 3 Sonnet"
        provider = "anthropic"
        deploymentType = "CLOUD"
        modelId = "claude-3-sonnet-20240229"
        apiKey = "sk-ant-test-key-for-e2e"
    },
    @{
        code = "ollama-llama3"
        name = "Ollama Llama 3 70B"
        provider = "ollama"
        deploymentType = "ON_PREMISE"
        modelId = "llama3:70b"
        apiEndpoint = "http://localhost:11434/v1"
    },
    @{
        code = "ollama-qwen3-coder-next"
        name = "Ollama Qwen3 Coder Next"
        provider = "ollama"
        deploymentType = "ON_PREMISE"
        modelId = "qwen3-coder-next"
        apiEndpoint = "http://localhost:11434/v1"
    },
    @{
        code = "ollama-llama3_2"
        name = "Ollama Llama 3.2"
        provider = "ollama"
        deploymentType = "ON_PREMISE"
        modelId = "llama3.2"
        apiEndpoint = "http://localhost:11434/v1"
    }
)

foreach ($model in $models) {
    Write-Host "   Creating model: $($model.name)..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/models" -Headers $adminHeaders -Body $model -IgnoreError
    if ($result) {
        Write-Host " created" -ForegroundColor Green
    }
}

# Configure GitHub Models (gpt-4o) with API key from .env file
$githubApiKey = $env:GITHUB_GPT_4O_KEY
if ($githubApiKey) {
    Write-Host "   Configuring GitHub Models (github-gpt-4o) with API key..." -NoNewline
    $updateBody = @{
        apiKey = $githubApiKey
    }
    $result = Invoke-Api -Method PUT -Uri "$ApiUrl/admin/models/github-gpt-4o" -Headers $adminHeaders -Body $updateBody -IgnoreError
    if ($result) {
        Write-Host " configured" -ForegroundColor Green
    }
} else {
    Write-Host "   WARNING: GITHUB_GPT_4O_KEY not set in .env - agents will not have model credentials" -ForegroundColor Red
}
Write-Host ""

# ============================================================
# STEP 4: Create Agent Profiles
# ============================================================
Write-Host "4. Creating agent profile..." -ForegroundColor Yellow

$profiles = @(
    @{
        name = "Fullstack - Spring, Maven, React"
        description = "Full-stack developer profile for Java Spring Boot + Maven backend and React TypeScript frontend applications"
        capabilities = @("java:21", "maven", "spring-boot:3", "react:18", "typescript:5", "nodejs:20", "docker", "postgresql")
        # Use individual tool codes that match agent implementations
        toolCodes = @("read_file", "write_file", "list_directory", "search_files", "search_code", "get_file_tree", "delete_file", "create_directory", "git_status", "git_diff", "git_add", "git_commit", "git_push", "git_log", "git_branch", "git_merge")
        systemPrompt = @"
You are an expert full-stack developer. You have access to tools that let you interact with the filesystem and git repository.

CRITICAL RULES:
- You MUST use the provided tools to implement changes. NEVER output code as text in your response.
- Use write_file to create or update files. Always write the COMPLETE file content.
- If the project is empty or files don't exist yet, CREATE them from scratch using write_file.
- After writing all files, you MUST commit and push using git tools.
- Your final text response should be a brief summary of what you did, NOT code.

TECH STACK:
- Backend: Spring Boot 3.x, Java 21, Maven, Spring Data JPA, PostgreSQL
- Frontend: React 18, TypeScript, TanStack Query/Router, Tailwind CSS
- Follow SOLID principles, clean code, proper error handling

WORKFLOW (follow this exact order):
1. Use get_file_tree to understand the project structure (if empty, that's OK - create files from scratch)
2. If files exist, use read_file to examine them before modifying
3. Use write_file for EACH file you need to create or modify (write_file creates parent directories automatically)
4. Use git_add with "." to stage all changes
5. Use git_commit with a descriptive commit message
6. Use git_push to push to remote
7. Respond with a brief summary of changes made

IMPORTANT: If the repo is empty or missing expected files, CREATE them. Do NOT give up or report errors.
NEVER skip steps 3-6. ALWAYS write files using tools and commit.
"@
        defaultModel = "ollama-qwen3-coder-next"
    },
    @{
        name = "Chat Assistant"
        description = "General-purpose conversational assistant for interactive chat sessions"
        capabilities = @("conversational")
        toolCodes = @()
        systemPrompt = @"
You are a helpful, knowledgeable AI assistant. You answer questions clearly and concisely, help with brainstorming, writing, analysis, and general problem-solving. When you don't know something, say so honestly. Be friendly and professional.
"@
        defaultModel = "ollama-llama3_2"
    }
)

$profileIds = @{}
foreach ($profile in $profiles) {
    Write-Host "   Creating profile: $($profile.name)..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders -Body $profile -IgnoreError
    if ($result) {
        $profileIds[$profile.name] = $result.id
        Write-Host " created (ID: $($result.id))" -ForegroundColor Green
    } else {
        Write-Host "" # Newline after "(skipped)"
        # Profile already exists - update it to ensure tools are set
        $existingProfiles = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders
        # Handle single object vs array
        if ($existingProfiles -and $existingProfiles -isnot [Array]) {
            $existingProfiles = @($existingProfiles)
        }
        $existing = $existingProfiles | Where-Object { $_.name -eq $profile.name }
        if ($existing) {
            $profileIds[$profile.name] = $existing.id
            # Update the existing profile with current tool codes
            Write-Host "   Updating existing profile (ID: $($existing.id))..." -NoNewline
            $updateResult = Invoke-Api -Method PUT -Uri "$ApiUrl/admin/agent-profiles/$($existing.id)" -Headers $adminHeaders -Body $profile -IgnoreError
            if ($updateResult) {
                Write-Host " updated" -ForegroundColor Green
            } else {
                Write-Host " update failed" -ForegroundColor Yellow
            }
        } else {
            Write-Host "   ERROR: Could not find profile by name" -ForegroundColor Red
        }
    }
}
Write-Host ""

# ============================================================
# STEP 5: Create Agents
# ============================================================
Write-Host "5. Creating agent..." -ForegroundColor Yellow

# Ensure we have profile IDs
if (-not $profileIds["Fullstack - Spring, Maven, React"]) {
    Write-Host "   Fetching profile IDs..." -NoNewline
    $allProfiles = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders
    # Handle single object vs array
    if ($allProfiles -and $allProfiles -isnot [Array]) {
        $allProfiles = @($allProfiles)
    }
    foreach ($p in $allProfiles) {
        $profileIds[$p.name] = $p.id
    }
    Write-Host " done" -ForegroundColor Green
}

$fullstackProfileId = $profileIds["Fullstack - Spring, Maven, React"]
if (-not $fullstackProfileId) {
    Write-Host "   ERROR: Could not find 'Fullstack - Spring, Maven, React' profile" -ForegroundColor Red
    Write-Host "   Available profiles: $($profileIds.Keys -join ', ')" -ForegroundColor Gray
    exit 1
}
Write-Host "   Using profile ID: $fullstackProfileId" -ForegroundColor Gray

$chatProfileId = $profileIds["Chat Assistant"]
if (-not $chatProfileId) {
    Write-Host "   WARNING: Could not find 'Chat Assistant' profile" -ForegroundColor Yellow
} else {
    Write-Host "   Chat profile ID: $chatProfileId" -ForegroundColor Gray
}

# Helper: create-or-fetch an agent and capture its registration key.
function Create-OrFetchAgent {
    param(
        [hashtable]$AgentRequest,
        [hashtable]$AgentIds,
        [string]$ApiUrl,
        [hashtable]$AdminHeaders
    )
    $name = $AgentRequest.name
    $key = $null

    Write-Host "   Creating agent: $name..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/agents" -Headers $AdminHeaders -Body $AgentRequest -IgnoreError
    if ($result -and $result.agent) {
        $AgentIds[$name] = $result.agent.id
        $key = $result.registrationKey
        Write-Host " created (ID: $($result.agent.id))" -ForegroundColor Green
        Write-Host "   Registration Key: $($key.Substring(0, 20))..." -ForegroundColor Green
    } else {
        Write-Host "" # Newline after "(skipped)"
        Write-Host "   Looking up existing agent..." -NoNewline
        $existingAgents = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agents" -Headers $AdminHeaders
        if ($existingAgents -and $existingAgents -isnot [Array]) {
            $existingAgents = @($existingAgents)
        }
        $existing = $existingAgents | Where-Object { $_.name -eq $name }
        if ($existing) {
            $AgentIds[$name] = $existing.id
            Write-Host " found (ID: $($existing.id))" -ForegroundColor Yellow
            Write-Host "   Regenerating registration key..." -NoNewline
            try {
                $regKeyResponse = Invoke-Api -Method POST `
                    -Uri "$ApiUrl/admin/agents/$($existing.id)/regenerate-key" `
                    -Headers $AdminHeaders
                if ($regKeyResponse -and $regKeyResponse.registrationKey) {
                    $key = $regKeyResponse.registrationKey
                    Write-Host " done" -ForegroundColor Green
                } else {
                    Write-Host " no key returned" -ForegroundColor Red
                }
            } catch {
                Write-Host " failed: $($_.Exception.Message)" -ForegroundColor Red
            }
        } else {
            Write-Host " not found" -ForegroundColor Red
        }
    }
    return $key
}

$agentIds = @{}

# --- Fullstack agent (workflow tasks) ---
$fullstackAgentRequest = @{
    name = "Addressbook Fullstack Agent"
    description = "Full-stack agent for the Address Book application - handles backend, frontend, testing, and documentation"
    profileId = $fullstackProfileId
    maxInstances = 5
}
$regKey = Create-OrFetchAgent -AgentRequest $fullstackAgentRequest -AgentIds $agentIds `
    -ApiUrl $ApiUrl -AdminHeaders $adminHeaders

if (-not $regKey) {
    Write-Host "Error: Failed to get registration key for fullstack agent" -ForegroundColor Red
    exit 1
}

# --- Chat agent (conversational sessions) ---
$chatRegKey = $null
if ($chatProfileId) {
    $chatAgentRequest = @{
        name = "Chat Assistant Agent"
        description = "General-purpose conversational assistant for interactive chat testing"
        profileId = $chatProfileId
        maxInstances = 3
    }
    $chatRegKey = Create-OrFetchAgent -AgentRequest $chatAgentRequest -AgentIds $agentIds `
        -ApiUrl $ApiUrl -AdminHeaders $adminHeaders
    if (-not $chatRegKey) {
        Write-Host "   WARNING: Failed to get registration key for chat agent" -ForegroundColor Yellow
    }
}
Write-Host ""

# ============================================================
# STEP 6: Save Registration Keys to .env files
# ============================================================
Write-Host "6. Saving registration keys..." -ForegroundColor Yellow

# Fullstack agent key → agent-registration.env (used by start-agent.ps1 for workflow tasks)
$envContent = @"
# Myrmec Agent Registration Key
# Created: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
# Agent: Addressbook Fullstack Agent

MYRMEC_REGISTRATION_KEY=$regKey
MYRMEC_ENGINE_URL=$BaseUrl
MYRMEC_AGENT_NAME=e2e-test-agent
MYRMEC_HEARTBEAT_INTERVAL=30
"@

$envContent | Out-File -FilePath $OutputPath -Encoding UTF8
Write-Host "   Fullstack agent key saved to: $OutputPath" -ForegroundColor Green

# Chat agent key → chat-agent-registration.env (used by start-chat-agent.ps1)
if ($chatRegKey) {
    $chatEnvPath = Join-Path $ScriptDir "..\chat-agent-registration.env"
    $chatEnvContent = @"
# Myrmec Chat Agent Registration Key
# Created: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
# Agent: Chat Assistant Agent

MYRMEC_REGISTRATION_KEY=$chatRegKey
MYRMEC_ENGINE_URL=$BaseUrl
MYRMEC_AGENT_NAME=e2e-chat-agent
"@

    $chatEnvContent | Out-File -FilePath $chatEnvPath -Encoding UTF8
    Write-Host "   Chat agent key saved to: $chatEnvPath" -ForegroundColor Green
}
Write-Host ""

# ============================================================
# STEP 7: Create Project
# ============================================================
Write-Host "7. Creating project..." -ForegroundColor Yellow

$projectBody = @{
    name = "Address Book Application"
    description = "Full-stack contact management application with Java backend and React frontend"
    workspaceRepoUrl = "https://github.com/myrmecai/examples.git"
    workspaceRepoBranch = "main"
}

$project = Invoke-Api -Method POST -Uri "$ApiUrl/projects" -Headers $adminHeaders -Body $projectBody -IgnoreError
if (-not $project -or -not $project.id) {
    # Project already exists — look it up by listing and filtering by name
    Write-Host "   Project already exists, looking up..." -NoNewline
    $existingProjects = Invoke-Api -Method GET -Uri "$ApiUrl/projects" -Headers $adminHeaders
    if ($existingProjects -and $existingProjects -isnot [Array]) { $existingProjects = @($existingProjects) }
    $project = $existingProjects | Where-Object { $_.name -eq $projectBody.name } | Select-Object -First 1
    if ($project) {
        Write-Host " found (ID: $($project.id))" -ForegroundColor Yellow
    } else {
        Write-Host " not found" -ForegroundColor Red
        exit 1
    }
}

$projectId = $project.id
Write-Host "   Project ID: $projectId" -ForegroundColor Green
Write-Host "   Name: $($project.name)" -ForegroundColor Green

# Assign PROJECT_OWNER to the tester user on this project so canEdit/canOwn
# gates pass for Assistant creation. The bootstrap admin has PLATFORM_ADMIN +
# ORG_ADMIN but those are intentionally isolated from data-access roles
# (separation of duties), and system users' roles cannot be modified.
$testerUserId = $testerUserId
if ($testerUserId) {
    Write-Host "   Assigning PROJECT_OWNER to tester on project..." -NoNewline
    $roleBody = @{ role = "PROJECT_OWNER"; scopeType = "PROJECT"; projectId = $projectId }
    $roleResult = Invoke-Api -Method POST -Uri "$ApiUrl/admin/users/$testerUserId/roles" -Headers $adminHeaders -Body $roleBody -IgnoreError
    if ($roleResult) {
        Write-Host " assigned" -ForegroundColor Green
    } else {
        Write-Host " (already assigned or failed)" -ForegroundColor Yellow
    }

    # Log in as the tester to get a token with project-scoped roles for
    # Assistant creation (the admin token lacks EDITOR on the project).
    Write-Host "   Logging in as tester for assistant creation..." -NoNewline
    $testerLogin = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body @{ email = "tester@e2e-test.local"; password = "E2eTest@123!" } -IgnoreError
    if ($testerLogin -and $testerLogin.accessToken) {
        $script:testerToken = $testerLogin.accessToken
        Write-Host " logged in" -ForegroundColor Green
    } else {
        Write-Host " failed" -ForegroundColor Red
    }
}
Write-Host ""

# ============================================================
# STEP 8: Create Workflow
# ============================================================
Write-Host "8. Creating workflow..." -ForegroundColor Yellow

# Get the fullstack profile ID
$fullstackProfileId = $profileIds["Fullstack - Spring, Maven, React"]

if (-not $fullstackProfileId) {
    Write-Host "   Fetching profile ID..." -ForegroundColor Yellow
    $allProfiles = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agent-profiles" -Headers $adminHeaders
    $fullstackProfileId = ($allProfiles | Where-Object { $_.name -eq "Fullstack - Spring, Maven, React" }).id
}

$workflow = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows" -Headers $adminHeaders -Body @{
    projectId = $projectId
    name = "Feature Implementation"
    description = "End-to-end workflow for implementing a new feature with code generation, review, and documentation"
    steps = @(
        @{
            id = "generate"
            name = "Generate Code"
            agentProfileId = $fullstackProfileId
            prompt = 'Implement the requested feature by writing code files using your tools. Start by checking the project structure with get_file_tree. If files already exist, read them first. Then use write_file to create or update EACH file needed. If the project is empty, create all files from scratch including pom.xml, package.json, etc. After writing ALL files, run: git_add("."), then git_commit, then git_push. Do NOT output code as text. If previous review comments are provided in your input, read them carefully and fix ALL the issues mentioned before committing.'
            dependsOn = @()
            transitions = @{ default = "review" }
            timeoutSeconds = 600
            maxRetries = 2
        },
        @{
            id = "review"
            name = "Review Code"
            agentProfileId = $fullstackProfileId
            prompt = @'
Review the code in this repository against project standards.

STEPS:
1. Read ALL instruction files in .myrmec/instructions/ folder (use get_file_tree then read_file for each .md file there)
2. Read ALL source files in the project
3. Compare code against the standards and checklist from the instruction files
4. Write your review findings to a file called REVIEW.md at the project root using write_file
5. Use git_add("."), git_commit, and git_push to save the review

REVIEW DECISION:
After reviewing, you MUST decide:
- If the code has NO significant issues (only minor or cosmetic issues), the review passes
- If the code has significant issues that must be fixed, the review fails

You MUST end your response with EXACTLY one of these lines:
OUTCOME: approved
OUTCOME: changes_requested
'@
            dependsOn = @("generate")
            transitions = @{ approved = "finalize"; changes_requested = "generate" }
            timeoutSeconds = 300
            maxRetries = 1
        },
        @{
            id = "finalize"
            name = "Finalize"
            agentProfileId = $fullstackProfileId
            prompt = @'
The code has been reviewed and approved. Perform the final steps:

1. Update README.md with setup instructions, API docs, and usage examples
2. Remove or clean up REVIEW.md if it exists
3. Use git_add("."), git_commit, and git_push to save your changes on the current feature branch
4. Switch to main branch using git_branch with branch_name="main"
5. Merge the feature branch into main using git_merge with the feature branch name (check git_branch output for the myrmec/* branch name)
6. Push main to remote using git_push
7. Switch back to the feature branch (do NOT delete it)

Respond with a brief summary of the completed workflow including the feature branch name.
'@
            dependsOn = @("review")
            timeoutSeconds = 300
            maxRetries = 1
        }
    )
    inputSchema = @{
        type = "object"
        properties = @{
            featureName = @{ type = "string"; description = "Name of the feature to implement" }
            requirements = @{ type = "string"; description = "Detailed requirements for the feature" }
            targetFiles = @{ type = "array"; items = @{ type = "string" }; description = "Files to create or modify" }
        }
        required = @("featureName", "requirements")
    }
} -IgnoreError

if (-not $workflow -or -not $workflow.id) {
    # Workflow already exists — look it up
    Write-Host "   Workflow already exists, looking up..." -NoNewline
    $existingWorkflows = Invoke-Api -Method GET -Uri "$ApiUrl/projects/$projectId/workflows" -Headers $adminHeaders -IgnoreError
    if ($existingWorkflows) {
        if ($existingWorkflows -isnot [Array]) { $existingWorkflows = @($existingWorkflows) }
        $workflow = $existingWorkflows | Where-Object { $_.name -eq "Feature Implementation" } | Select-Object -First 1
    }
    if ($workflow) {
        Write-Host " found (ID: $($workflow.id))" -ForegroundColor Yellow
    } else {
        Write-Host " not found" -ForegroundColor Red
        $workflow = $null
    }
}

if (-not $workflow -or -not $workflow.id) {
    Write-Host "   ERROR: Could not create or find workflow" -ForegroundColor Red
    exit 1
}

$workflowId = $workflow.id
Write-Host "   Workflow ID: $workflowId" -ForegroundColor Green
Write-Host "   Name: $($workflow.name)" -ForegroundColor Green
Write-Host "   Steps: $($workflow.steps.Count)" -ForegroundColor Green

# Publish the workflow (ignore error if already published)
Write-Host "   Publishing workflow..." -NoNewline
$publishResult = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows/$workflowId/publish" -Headers $adminHeaders -IgnoreError
if ($publishResult) {
    Write-Host " published" -ForegroundColor Green
} else {
    Write-Host " (already published or failed)" -ForegroundColor Yellow
}

# Start a workflow execution
Write-Host "   Starting workflow execution..." -NoNewline
$executionRequest = @{
    workflowId = $workflowId
    input = @{
        featureName = "Contact Search"
        requirements = "Add a search feature to filter contacts by name, email, or phone number. Backend should support partial matching. Frontend should have a search input with debounced API calls."
        targetFiles = @("src/main/java/com/example/addressbook/controller/ContactController.java", "src/main/java/com/example/addressbook/service/ContactService.java", "frontend/src/components/ContactList.tsx")
    }
}
$execution = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/workflows/$workflowId/requests" -Headers $adminHeaders -Body $executionRequest -IgnoreError
$executionId = if ($execution) { $execution.id } else { "(skipped)" }
if ($execution) {
    Write-Host " started (ID: $executionId)" -ForegroundColor Green
} else {
    Write-Host " (skipped or already started)" -ForegroundColor Yellow
}
Write-Host ""

# ============================================================
# STEP 9: Create Organization-Level Knowledge Documents
# ============================================================
Write-Host "9. Creating organization-level knowledge documents..." -ForegroundColor Yellow

$codingStandards = @"
# Coding Standards

## General Principles
- Use clear, descriptive naming conventions
- Classes: PascalCase (e.g., UserService, ContactController)
- Methods: camelCase (e.g., getUserById, handleClick)
- Constants: UPPER_SNAKE_CASE (e.g., MAX_RETRIES, DEFAULT_TIMEOUT)

## Error Handling
- All code must have proper error handling with meaningful messages
- Never swallow exceptions silently
- Log errors at appropriate levels (DEBUG, INFO, WARN, ERROR)

## Documentation
- All public APIs must have documentation
- Include examples in documentation
- Keep documentation up to date with code changes
"@

$securityGuidelines = @"
# Security Guidelines

## Input Validation
- All user input must be validated and sanitized
- Use parameterized queries to prevent SQL injection
- Validate file uploads (type, size, content)

## Authentication & Authorization
- Never log sensitive information (passwords, tokens, PII)
- Use secure password hashing (bcrypt, argon2)
- Implement proper session management
- Apply principle of least privilege

## API Security
- Implement rate limiting on public endpoints
- Use HTTPS for all communications
- Validate and sanitize all API inputs
- Return generic error messages to clients
"@

$orgDocs = @(
    @{
        name = "Coding Standards"
        category = "STANDARD"
        priority = 100
        appliesTo = @("**")
        content = $codingStandards
    },
    @{
        name = "Security Guidelines"
        category = "REQUIREMENT"
        priority = 150
        appliesTo = @("**")
        content = $securityGuidelines
    }
)

foreach ($doc in $orgDocs) {
    Write-Host "   Creating: $($doc.name)..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/knowledge" -Headers $adminHeaders -Body $doc -IgnoreError
    if ($result) {
        Write-Host " created (priority: $($doc.priority))" -ForegroundColor Green
    }
}
Write-Host ""

# ============================================================
# STEP 10: Create Project-Level Knowledge Documents
# ============================================================
Write-Host "10. Creating project-level knowledge documents..." -ForegroundColor Yellow

$javaStandards = @"
# Java Spring Boot Standards

## Language Features
- Use Java 21 features including records and sealed classes
- Prefer var for local variables with obvious types
- Use Optional for nullable return values

## Spring Best Practices
- Prefer constructor injection over field injection
- Use @Transactional at service layer only
- DTOs should be Java records
- Use @Valid for request validation

## Testing
- Write unit tests for all business logic
- Use @SpringBootTest for integration tests
- Mock external dependencies in unit tests
"@

$reactStandards = @"
# React TypeScript Standards

## Component Design
- Use functional components with hooks only
- Prefer composition over props drilling
- Keep components small and focused

## State Management
- Use TanStack Query for server state
- Use Zustand for client state if needed
- Avoid prop drilling with context or state management

## TypeScript
- Use TypeScript strict mode
- Define interfaces for all props
- Avoid using 'any' type
- Use discriminated unions for complex state
"@

$restApiConventions = @"
# REST API Conventions

## URL Structure
- Use singular nouns in paths: /api/v1/contact not /contacts
- Use lowercase with hyphens: /api/v1/user-profile
- Version APIs: /api/v1/, /api/v2/

## HTTP Methods & Status Codes
- GET: Retrieve resources (200 OK)
- POST: Create resources (201 Created with Location header)
- PUT: Update resources (200 OK)
- DELETE: Remove resources (204 No Content)

## Response Format
- Use JSON:API-style pagination
- Include total count in list responses
- Return consistent error format
"@

$architecture = @"
# Address Book Architecture

## Domain Model
- Contact: id, firstName, lastName, email, phone, addresses
- Address: street, city, state, postalCode, country
- Group: id, name, description, contacts

## Backend Stack
- Spring Boot 3.x with Java 21
- PostgreSQL database with Spring Data JPA
- REST API at /api/v1/contact

## Frontend Stack
- React 18 with TypeScript
- TanStack Query for data fetching
- TanStack Router for routing
- Component structure: pages, components, api, hooks

## Deployment
- Docker containers for both services
- Kubernetes for orchestration
- PostgreSQL as managed service
"@

$projectDocs = @(
    @{
        name = "Java Spring Standards"
        category = "INSTRUCTION"
        priority = 200
        appliesTo = @("**/*.java")
        content = $javaStandards
    },
    @{
        name = "React TypeScript Standards"
        category = "INSTRUCTION"
        priority = 200
        appliesTo = @("**/*.tsx", "**/*.ts")
        content = $reactStandards
    },
    @{
        name = "REST API Conventions"
        category = "INSTRUCTION"
        priority = 180
        appliesTo = @("**/controller/*.java", "**/api/*.ts")
        content = $restApiConventions
    },
    @{
        name = "Architecture"
        category = "ARCHITECTURE"
        priority = 250
        appliesTo = @("**")
        content = $architecture
    }
)

foreach ($doc in $projectDocs) {
    Write-Host "   Creating: $($doc.name)..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge" -Headers $adminHeaders -Body $doc -IgnoreError
    if ($result) {
        Write-Host " created (priority: $($doc.priority), applies: $($doc.appliesTo -join ', '))" -ForegroundColor Green
    }
}
Write-Host ""

# ============================================================
# STEP 11: Enable HITL on Destructive Actions (Phase 7)
# ============================================================
# Flips the project's autoHitlOnDestructive flag so any agent attempt
# to invoke a DESTRUCTIVE or IRREVERSIBLE tool pauses for explicit
# approval. The UI's approval card surfaces these requests.
Write-Host "11. Enabling autoHitlOnDestructive on project..." -ForegroundColor Yellow

$hitlBody = @{
    autoHitlOnDestructive = $true
}
$updated = Invoke-Api -Method PUT -Uri "$ApiUrl/projects/$projectId" -Headers $adminHeaders -Body $hitlBody -IgnoreError
if ($updated) {
    Write-Host "   autoHitlOnDestructive = true" -ForegroundColor Green
} else {
    Write-Host "   (skipped or failed)" -ForegroundColor Yellow
}
Write-Host ""

# ============================================================
# STEP 12: Create a Model Provider (Phase 10 #70)
# ============================================================
# Demonstrates the new /admin/providers CRUD surface. System
# providers (openai, anthropic, ollama) are Liquibase-seeded; here
# we add a custom one so admins can see the UI flow end-to-end.
Write-Host "12. Creating non-system model provider..." -ForegroundColor Yellow

$providerBody = @{
    code = "local-vllm"
    name = "Local vLLM"
    baseUrl = "http://localhost:8000/v1"
    deploymentType = "ON_PREMISE"
    requiresAuth = $false
    healthEndpoint = "/health"
    modelsEndpoint = "/v1/models"
    docsUrl = "https://docs.vllm.ai"
    description = "Self-hosted vLLM inference server for OSS models"
}

$createdProvider = Invoke-Api -Method POST -Uri "$ApiUrl/admin/providers" -Headers $adminHeaders -Body $providerBody -IgnoreError
if ($createdProvider) {
    Write-Host "   Created provider: $($createdProvider.code) ($($createdProvider.name))" -ForegroundColor Green
} else {
    Write-Host "   Provider already exists (or skipped)" -ForegroundColor Gray
}
Write-Host ""

# ============================================================
# STEP 13: Create Quotas (Phase 8 — token + cost caps)
# ============================================================
# One TOKENS quota and one COST_USD_CENTS quota at PROJECT scope.
# Both run in enforced mode so the 429 banner is exercised when the
# project burns through its allowance.
Write-Host "13. Creating project quotas..." -ForegroundColor Yellow

$quotas = @(
    @{
        scopeType    = "PROJECT"
        scopeId      = $projectId
        resourceType = "TOKENS"
        period       = "DAILY"
        limitAmount  = 500000
        enforced     = $true
        tags         = @{ source = "setup-e2e-data" }
    },
    @{
        scopeType    = "PROJECT"
        scopeId      = $projectId
        resourceType = "COST_USD_CENTS"
        period       = "MONTHLY_CALENDAR"
        limitAmount  = 5000   # $50.00
        enforced     = $true
        tags         = @{ source = "setup-e2e-data" }
    }
)

foreach ($q in $quotas) {
    Write-Host "   Creating $($q.resourceType) $($q.period) quota (limit=$($q.limitAmount))..." -NoNewline
    $result = Invoke-Api -Method POST -Uri "$ApiUrl/admin/quotas" -Headers $adminHeaders -Body $q -IgnoreError
    if ($result) {
        Write-Host " created (ID: $($result.id))" -ForegroundColor Green
    }
}
Write-Host ""

# ============================================================
# STEP 14: Verify audit log + agent health endpoints (Phase 9b/9c/9d)
# ============================================================
# Read-only sanity checks. The audit log should already contain
# entries from the role-grant, secret-CRUD, and quota-CRUD operations
# performed above. Agent health gives the dispatcher's view of the
# registered agent.
Write-Host "14. Verifying audit log + agent health endpoints..." -ForegroundColor Yellow

$auditEntries = Invoke-Api -Method GET -Uri "$ApiUrl/audit-log?size=5" -Headers $adminHeaders -IgnoreError
if ($auditEntries) {
    $count = if ($auditEntries.totalElements) { $auditEntries.totalElements } else { ($auditEntries.content | Measure-Object).Count }
    Write-Host "   Audit log reachable. Recent entries: $count" -ForegroundColor Green
} else {
    Write-Host "   Audit log endpoint not reachable (may not be wired in this build)" -ForegroundColor Yellow
}

if ($agentIds["Addressbook Fullstack Agent"]) {
    $agentId = $agentIds["Addressbook Fullstack Agent"]
    $health = Invoke-Api -Method GET -Uri "$ApiUrl/admin/agents/$agentId/health" -Headers $adminHeaders -IgnoreError
    if ($health) {
        Write-Host "   Agent health: status=$($health.status) instances=$($health.instanceCount)" -ForegroundColor Green
    } else {
        Write-Host "   Agent health endpoint not reachable" -ForegroundColor Yellow
    }
}
Write-Host ""

# ============================================================
# STEP 15: Create + publish an Assistant, then sample Conversations
# ============================================================
# The MyWork Conversations tab lists Assistants (the versioned definition
# of a conversational service), not raw Agents. An Assistant pins an agent
# profile + published version; a conversation session pins the Assistant.
# Flow: create Assistant (parent + initial Draft) → publish Draft → create
# conversation with assistantId (engine auto-resolves the agent host from
# the pinned profile).
Write-Host "15. Creating + publishing Assistant..." -ForegroundColor Yellow

$chatAssistantId = $null

# Use the tester token (has PROJECT_OWNER on the project) for Assistant
# creation — the admin token lacks EDITOR and the Assistant create endpoint
# requires @projectAccess.canEdit.
$assistantHeaders = $adminHeaders
if ($testerToken) {
    $assistantHeaders = @{ "Authorization" = "Bearer $testerToken" }
}

if ($chatProfileId -and $agentIds["Chat Assistant Agent"]) {
    # 15a. Create the Assistant (parent + initial Draft seeded with the chat profile)
    $assistantBody = @{
        projectId       = $projectId
        name            = "Chat Assistant"
        description     = "General-purpose conversational assistant for interactive chat testing"
        agentProfileId  = $chatProfileId
    }
    $assistant = Invoke-Api -Method POST -Uri "$ApiUrl/assistants" -Headers $assistantHeaders -Body $assistantBody -IgnoreError
    if ($assistant -and $assistant.id) {
        $chatAssistantId = $assistant.id
        Write-Host "   Assistant created (ID: $chatAssistantId)" -ForegroundColor Green
    } else {
        Write-Host "   Assistant create (skipped or failed) - looking up existing..." -ForegroundColor Yellow
        $existingAssistants = Invoke-Api -Method GET -Uri "$ApiUrl/assistants?projectId=$projectId" -Headers $assistantHeaders -IgnoreError
        if ($existingAssistants) {
            if ($existingAssistants -isnot [Array]) { $existingAssistants = @($existingAssistants) }
            $existing = $existingAssistants | Where-Object { $_.name -eq "Chat Assistant" }
            if ($existing) {
                $chatAssistantId = $existing.id
                Write-Host "   Found existing Assistant (ID: $chatAssistantId)" -ForegroundColor Yellow
            }
        }
    }

    # 15b. Publish the Assistant (promotes the initial Draft → Published)
    if ($chatAssistantId) {
        Write-Host "   Publishing Assistant..." -NoNewline
        $published = Invoke-Api -Method POST -Uri "$ApiUrl/assistants/$chatAssistantId/publish" -Headers $assistantHeaders -IgnoreError
        if ($published -and $published.versionNumber) {
            Write-Host " published (v$($published.versionNumber))" -ForegroundColor Green
        } else {
            Write-Host " (already published or publish failed)" -ForegroundColor Yellow
        }
    }
} else {
    Write-Host "   Skipped - chat profile or chat agent not available" -ForegroundColor Yellow
}
Write-Host ""

# ============================================================
# STEP 16: Create sample Conversations
# ============================================================
Write-Host "16. Creating sample conversations..." -ForegroundColor Yellow

$conversationId = $null
$chatConversationId = $null

# Workflow-focused conversation (fullstack agent)
$conversationBody = @{
    projectId = $projectId
    title     = "Address Book - kickoff chat"
    agentId   = $agentIds["Addressbook Fullstack Agent"]
}
$conversation = Invoke-Api -Method POST -Uri "$ApiUrl/conversations" -Headers $adminHeaders -Body $conversationBody -IgnoreError
if ($conversation -and $conversation.id) {
    $conversationId = $conversation.id
    Write-Host "   Workflow conversation ID: $conversationId" -ForegroundColor Green
    Write-Host "   Title: $($conversation.title)" -ForegroundColor Green
} else {
    Write-Host "   Workflow conversation (skipped or failed)" -ForegroundColor Yellow
}

# Chat-ready conversation — pinned to the published Assistant. The engine
# auto-resolves the agent host from the assistant's pinned profile.
if ($chatAssistantId) {
    $chatConvBody = @{
        projectId   = $projectId
        title       = "Chat with Assistant"
        assistantId = $chatAssistantId
    }
    $chatConversation = Invoke-Api -Method POST -Uri "$ApiUrl/conversations" -Headers $adminHeaders -Body $chatConvBody -IgnoreError
    if ($chatConversation -and $chatConversation.id) {
        $chatConversationId = $chatConversation.id
        Write-Host "   Chat conversation ID: $chatConversationId" -ForegroundColor Green
        Write-Host "   Title: $($chatConversation.title)" -ForegroundColor Green
        Write-Host "   Assistant: $chatAssistantId" -ForegroundColor Green
    } else {
        Write-Host "   Chat conversation (skipped or failed)" -ForegroundColor Yellow
    }
} elseif ($agentIds["Chat Assistant Agent"]) {
    # Fallback: pin directly to the agent host if no assistant was created
    $chatConvBody = @{
        projectId = $projectId
        title     = "Chat with Assistant"
        agentId   = $agentIds["Chat Assistant Agent"]
    }
    $chatConversation = Invoke-Api -Method POST -Uri "$ApiUrl/conversations" -Headers $adminHeaders -Body $chatConvBody -IgnoreError
    if ($chatConversation -and $chatConversation.id) {
        $chatConversationId = $chatConversation.id
        Write-Host "   Chat conversation ID: $chatConversationId (agent-pinned fallback)" -ForegroundColor Green
    }
}
Write-Host ""

# ============================================================
# Summary
# ============================================================
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "     E2E Data Setup Complete!" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Created:" -ForegroundColor Green
Write-Host "  - 2 Test Users (joga.singh@gmail.com [VIEWER], tester@e2e-test.local [EDITOR + PROJECT_OWNER])"
Write-Host "  - 5 Tools (file-system, web-browser, code-interpreter, github-api, postgresql)"
Write-Host "  - 6 Models (gpt-4-turbo, gpt-4o, claude-3-sonnet, ollama-llama3, ollama-qwen3-coder-next, ollama-llama3.2)"
Write-Host "  - 2 Agent Profiles (Fullstack - Spring/Maven/React, Chat Assistant)"
Write-Host "  - 2 Agents (Addressbook Fullstack Agent, Chat Assistant Agent)"
Write-Host "  - 2 Registration Keys (agent-registration.env, chat-agent-registration.env)"
Write-Host "  - 1 Project with repo config (Address Book Application)"
Write-Host "  - 1 Published Workflow (Feature Implementation - 3 steps)"
Write-Host "  - 1 Workflow Execution (Contact Search feature)"
Write-Host "  - 2 Org-level Knowledge Docs (Coding Standards, Security Guidelines)"
Write-Host "  - 4 Project-level Knowledge Docs (Java, React, REST API, Architecture)"
Write-Host "  - autoHitlOnDestructive enabled on project (Phase 7)"
Write-Host "  - 1 Custom Model Provider (local-vllm) (Phase 10 #70)"
Write-Host "  - 2 Project Quotas: 500k TOKENS/day + `$50/month (Phase 8)"
Write-Host "  - Audit log + agent health endpoints verified (Phase 9b/9c/9d)"
Write-Host "  - 2 Sample Conversations (workflow kickoff + chat-ready)"
Write-Host "  - 1 Published Assistant (Chat Assistant - pins the chat profile)"
Write-Host ""
Write-Host "IDs:" -ForegroundColor Yellow
Write-Host "  Project ID:           $projectId"
Write-Host "  Workflow ID:          $workflowId"
Write-Host "  Execution ID:         $executionId"
Write-Host "  Workflow Conversation: $conversationId"
Write-Host "  Chat Assistant:       $chatAssistantId"
Write-Host "  Chat Conversation:    $chatConversationId"
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host "  Manual chat testing:"
Write-Host "    1. Start chat agent: .\start-chat-agent.ps1"
Write-Host "    2. Open UI: http://localhost:5173"
Write-Host "    3. Log in as tester@e2e-test.local / E2eTest@123!"
Write-Host "    4. My Work -> Conversations tab shows the 'Chat Assistant'"
Write-Host "    5. Click Open -> send a message (Ollama llama3.2 answers)"
Write-Host ""
Write-Host "  Workflow testing:"
Write-Host "    1. Start fullstack agent: .\start-agent.ps1"
Write-Host "    2. Agent will pick up tasks from the started execution"
Write-Host ""
