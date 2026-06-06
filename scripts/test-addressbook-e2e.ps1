# E2E Test: Address Book Knowledge Context Resolution
# PowerShell script to verify knowledge documents are correctly resolved for different file types

$ErrorActionPreference = "Stop"

$BaseUrl = "http://localhost:9090"
$ApiUrl = "$BaseUrl/api/v1"

Write-Host "=== Address Book Knowledge E2E Test ===" -ForegroundColor Cyan
Write-Host ""

# Helper function to make authenticated API calls
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        [object]$Body = $null
    )
    
    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers + @{ "Content-Type" = "application/json" }
    }
    
    if ($Body) {
        $params["Body"] = ($Body | ConvertTo-Json -Depth 10)
    }
    
    return Invoke-RestMethod @params
}

# ============================================================
# STEP 1: Login as Admin
# ============================================================
Write-Host "1. Logging in as admin..." -ForegroundColor Yellow

$loginBody = @{
    email = "admin@e2e-test.local"
    password = "E2eTest@123!"
}

$loginResponse = Invoke-Api -Method POST -Uri "$ApiUrl/auth/login" -Body $loginBody
$adminToken = $loginResponse.accessToken
$adminHeaders = @{ "Authorization" = "Bearer $adminToken" }

Write-Host "   Admin logged in successfully" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 2: Create Project
# ============================================================
Write-Host "2. Creating project..." -ForegroundColor Yellow

$project = Invoke-Api -Method POST -Uri "$ApiUrl/projects" -Headers $adminHeaders -Body @{
    name = "Address Book Application"
    description = "Contact management application with Java backend and React frontend"
    workspaceRepoUrl = "https://github.com/myrmecai/examples.git"
}

$projectId = $project.id
Write-Host "   Project ID: $projectId" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 2b: Create Git Credential Secret (for private repos)
# ============================================================
Write-Host "2b. Creating Git credential secret..." -ForegroundColor Yellow

# Load GitHub token from .env file if present
$envFile = Join-Path $PSScriptRoot ".env"
$gitToken = $null
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match "^GITHUB_GPT_4O_KEY=(.+)$") {
            $gitToken = $matches[1]
        }
    }
}

if ($gitToken) {
    try {
        $secret = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/secrets" -Headers $adminHeaders -Body @{
            toolCode = "git"
            name = "token"
            value = $gitToken
        }
        Write-Host "   Git token configured for private repo access" -ForegroundColor Green
    } catch {
        Write-Host "   Warning: Failed to create git secret (may already exist): $($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "   No GITHUB_GPT_4O_KEY in .env, skipping private repo token" -ForegroundColor Yellow
}
Write-Host ""

# ============================================================
# STEP 3: Create Organization-Level Knowledge Documents
# ============================================================
Write-Host "3. Creating organization-level knowledge documents..." -ForegroundColor Yellow

# Coding Standards (lowest priority, applies to all)
$codingStandards = @"
# Coding Standards

Use clear, descriptive naming conventions:
- Classes: PascalCase
- Methods: camelCase
- Constants: UPPER_SNAKE_CASE

All code must have proper error handling with meaningful messages.
Include logging at appropriate levels (DEBUG, INFO, WARN, ERROR).
"@

$doc1 = Invoke-Api -Method POST -Uri "$ApiUrl/admin/knowledge" -Headers $adminHeaders -Body @{
    name = "Coding Standards"
    category = "STANDARD"
    priority = 100
    appliesTo = @("**")
    content = $codingStandards
}
$doc1Id = $doc1.id
Write-Host "   Created: $($doc1.name) (priority: $($doc1.priority))" -ForegroundColor Green

# Security Guidelines (medium priority, applies to all)
$securityGuidelines = @"
# Security Guidelines

All user input must be validated and sanitized.
Never log sensitive information (passwords, tokens, PII).
Use parameterized queries to prevent SQL injection.
Implement rate limiting on public endpoints.
"@

$doc2 = Invoke-Api -Method POST -Uri "$ApiUrl/admin/knowledge" -Headers $adminHeaders -Body @{
    name = "Security Guidelines"
    category = "REQUIREMENT"
    priority = 150
    appliesTo = @("**")
    content = $securityGuidelines
}
$doc2Id = $doc2.id
Write-Host "   Created: $($doc2.name) (priority: $($doc2.priority))" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 4: Create Project-Level Knowledge Documents
# ============================================================
Write-Host "4. Creating project-level knowledge documents..." -ForegroundColor Yellow

# Java Spring Standards (high priority, applies to *.java)
$javaStandards = @"
# Java Spring Boot Standards

Use Java 21 features including records and sealed classes.
Prefer constructor injection over field injection.
Use @Transactional at service layer only.
DTOs should be Java records.
Use Optional for nullable return values.
"@

$doc3 = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge" -Headers $adminHeaders -Body @{
    name = "Java Spring Standards"
    category = "INSTRUCTION"
    priority = 200
    appliesTo = @("**/*.java")
    content = $javaStandards
}
$doc3Id = $doc3.id
Write-Host "   Created: $($doc3.name) (priority: $($doc3.priority), applies: **/*.java)" -ForegroundColor Green

# React TypeScript Standards (high priority, applies to *.tsx, *.ts)
$reactStandards = @"
# React TypeScript Standards

Use functional components with hooks only.
Use TanStack Query for server state management.
Use Zustand for client state if needed.
Prefer composition over props drilling.
Use TypeScript strict mode.
"@

$doc4 = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge" -Headers $adminHeaders -Body @{
    name = "React TypeScript Standards"
    category = "INSTRUCTION"
    priority = 200
    appliesTo = @("**/*.tsx", "**/*.ts")
    content = $reactStandards
}
$doc4Id = $doc4.id
Write-Host "   Created: $($doc4.name) (priority: $($doc4.priority), applies: **/*.tsx, **/*.ts)" -ForegroundColor Green

# REST API Conventions (medium-high, applies to controller and api files)
$apiConventions = @"
# REST API Conventions

Use singular nouns in paths: /api/v1/contact not /contacts
Return 201 for successful POST with Location header.
Return 204 for successful DELETE.
Use JSON:API-style pagination.
"@

$doc5 = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge" -Headers $adminHeaders -Body @{
    name = "REST API Conventions"
    category = "INSTRUCTION"
    priority = 180
    appliesTo = @("**/controller/*.java", "**/api/*.ts")
    content = $apiConventions
}
$doc5Id = $doc5.id
Write-Host "   Created: $($doc5.name) (priority: $($doc5.priority))" -ForegroundColor Green

# Architecture Document (highest priority, applies to all)
$architecture = @"
# Address Book Architecture

## Domain Model
- Contact: id, firstName, lastName, email, phone, addresses
- Address: street, city, state, postalCode, country

## Backend
- Spring Boot 3.x with Java 21
- PostgreSQL database with Spring Data JPA
- REST API at /api/v1/contact

## Frontend
- React 18 with TypeScript
- TanStack Query for data fetching
- Component structure: pages, components, api, hooks
"@

$doc6 = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge" -Headers $adminHeaders -Body @{
    name = "Architecture"
    category = "ARCHITECTURE"
    priority = 250
    appliesTo = @("**")
    content = $architecture
}
$doc6Id = $doc6.id
Write-Host "   Created: $($doc6.name) (priority: $($doc6.priority))" -ForegroundColor Green
Write-Host ""

# ============================================================
# STEP 5: Test Context Resolution for Java Backend File
# ============================================================
Write-Host "5. Testing context resolution for backend Java file..." -ForegroundColor Yellow

$javaContext = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge/resolve" -Headers $adminHeaders -Body @{
    filePath = "src/main/java/com/example/addressbook/controller/ContactController.java"
}

Write-Host "   File: ContactController.java" -ForegroundColor Cyan
Write-Host "   Documents resolved: $($javaContext.knowledge.Count)" -ForegroundColor Green
Write-Host "   Total char count: $($javaContext.knowledgeCharCount)" -ForegroundColor Green

Write-Host "   Documents (priority order):" -ForegroundColor Cyan
foreach ($doc in $javaContext.knowledge) {
    Write-Host "      - $($doc.name) ($($doc.category), priority: $($doc.priority))" -ForegroundColor White
}
Write-Host ""

# Verify expected documents for Java controller
$javaDocNames = $javaContext.knowledge | ForEach-Object { $_.name }
$expectedJavaDocs = @("Architecture", "Java Spring Standards", "REST API Conventions", "Security Guidelines", "Coding Standards")

$missingDocs = $expectedJavaDocs | Where-Object { $_ -notin $javaDocNames }
if ($missingDocs) {
    Write-Host "   FAIL: Missing expected documents: $($missingDocs -join ', ')" -ForegroundColor Red
    exit 1
} else {
    Write-Host "   PASS: All expected documents present for Java controller" -ForegroundColor Green
}
Write-Host ""

# ============================================================
# STEP 6: Test Context Resolution for React Component
# ============================================================
Write-Host "6. Testing context resolution for frontend React component..." -ForegroundColor Yellow

$reactContext = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge/resolve" -Headers $adminHeaders -Body @{
    filePath = "src/components/ContactList.tsx"
}

Write-Host "   File: ContactList.tsx" -ForegroundColor Cyan
Write-Host "   Documents resolved: $($reactContext.knowledge.Count)" -ForegroundColor Green
Write-Host "   Total char count: $($reactContext.knowledgeCharCount)" -ForegroundColor Green

Write-Host "   Documents (priority order):" -ForegroundColor Cyan
foreach ($doc in $reactContext.knowledge) {
    Write-Host "      - $($doc.name) ($($doc.category), priority: $($doc.priority))" -ForegroundColor White
}
Write-Host ""

# Verify expected documents for React component
$reactDocNames = $reactContext.knowledge | ForEach-Object { $_.name }
$expectedReactDocs = @("Architecture", "React TypeScript Standards", "Security Guidelines", "Coding Standards")

$missingDocs = $expectedReactDocs | Where-Object { $_ -notin $reactDocNames }
if ($missingDocs) {
    Write-Host "   FAIL: Missing expected documents: $($missingDocs -join ', ')" -ForegroundColor Red
    exit 1
} else {
    Write-Host "   PASS: All expected documents present for React component" -ForegroundColor Green
}

# Verify Java-specific docs are NOT included
$unexpectedDocs = $reactDocNames | Where-Object { $_ -eq "Java Spring Standards" }
if ($unexpectedDocs) {
    Write-Host "   FAIL: Java-specific document incorrectly included in React context" -ForegroundColor Red
    exit 1
} else {
    Write-Host "   PASS: Java-specific documents correctly excluded from React context" -ForegroundColor Green
}
Write-Host ""

# ============================================================
# STEP 7: Test Context Resolution for API TypeScript Client
# ============================================================
Write-Host "7. Testing context resolution for TypeScript API client..." -ForegroundColor Yellow

$apiContext = Invoke-Api -Method POST -Uri "$ApiUrl/projects/$projectId/knowledge/resolve" -Headers $adminHeaders -Body @{
    filePath = "src/api/contactApi.ts"
}

Write-Host "   File: contactApi.ts" -ForegroundColor Cyan
Write-Host "   Documents resolved: $($apiContext.knowledge.Count)" -ForegroundColor Green

Write-Host "   Documents (priority order):" -ForegroundColor Cyan
foreach ($doc in $apiContext.knowledge) {
    Write-Host "      - $($doc.name) ($($doc.category), priority: $($doc.priority))" -ForegroundColor White
}
Write-Host ""

# Verify REST API conventions is included for api/*.ts
$apiDocNames = $apiContext.knowledge | ForEach-Object { $_.name }
if ("REST API Conventions" -notin $apiDocNames) {
    Write-Host "   FAIL: REST API Conventions should be included for api/*.ts files" -ForegroundColor Red
    exit 1
} else {
    Write-Host "   PASS: REST API Conventions correctly included for API client" -ForegroundColor Green
}
Write-Host ""

# ============================================================
# STEP 8: Test Priority Ordering
# ============================================================
Write-Host "8. Verifying priority ordering in resolved context..." -ForegroundColor Yellow

# Architecture (250) should come before Java Standards (200) which comes before Security (150)
$priorities = $javaContext.knowledge | ForEach-Object { $_.priority }
$sortedPriorities = $priorities | Sort-Object -Descending

if (($priorities -join ",") -eq ($sortedPriorities -join ",")) {
    Write-Host "   PASS: Documents are correctly ordered by priority (highest first)" -ForegroundColor Green
} else {
    Write-Host "   FAIL: Documents are not in correct priority order" -ForegroundColor Red
    Write-Host "   Expected: $($sortedPriorities -join ', ')" -ForegroundColor Red
    Write-Host "   Got: $($priorities -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host ""

# ============================================================
# STEP 9: Cleanup
# ============================================================
Write-Host "9. Cleaning up test data..." -ForegroundColor Yellow

# Delete project (cascades to project-level knowledge)
try {
    Invoke-Api -Method DELETE -Uri "$ApiUrl/projects/$projectId" -Headers $adminHeaders | Out-Null
    Write-Host "   Deleted project: $projectId" -ForegroundColor Green
} catch {
    Write-Host "   Warning: Could not delete project: $_" -ForegroundColor Yellow
}

# Delete organization-level knowledge documents
try {
    Invoke-Api -Method DELETE -Uri "$ApiUrl/admin/knowledge/$doc1Id" -Headers $adminHeaders | Out-Null
    Write-Host "   Deleted org knowledge: Coding Standards" -ForegroundColor Green
} catch {
    Write-Host "   Warning: Could not delete doc1: $_" -ForegroundColor Yellow
}

try {
    Invoke-Api -Method DELETE -Uri "$ApiUrl/admin/knowledge/$doc2Id" -Headers $adminHeaders | Out-Null
    Write-Host "   Deleted org knowledge: Security Guidelines" -ForegroundColor Green
} catch {
    Write-Host "   Warning: Could not delete doc2: $_" -ForegroundColor Yellow
}
Write-Host ""

# ============================================================
# SUMMARY
# ============================================================
Write-Host "=== All E2E Tests Passed! ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Summary:" -ForegroundColor White
Write-Host "  - Java controller receives: Architecture, Java Spring, REST API, Security, Coding" -ForegroundColor Green
Write-Host "  - React component receives: Architecture, React TS, Security, Coding" -ForegroundColor Green
Write-Host "  - TypeScript API client receives: Architecture, React TS, REST API, Security, Coding" -ForegroundColor Green
Write-Host "  - Documents are correctly ordered by priority (highest first)" -ForegroundColor Green
Write-Host ""
