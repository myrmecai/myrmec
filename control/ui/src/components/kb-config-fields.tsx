// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"

export interface ConfigField {
  key: string
  label: string
  type: "text" | "number" | "secret" | "textarea"
  required?: boolean
  placeholder?: string
  help?: string
  default?: string | number
}

/** Connector-specific config field definitions. */
export const CONNECTOR_CONFIGS: Record<string, ConfigField[]> = {
  git: [
    { key: "tokenSecret", label: "Token Secret Ref", type: "secret", placeholder: "github-pat", help: "Secret reference for the git access token (resolved via SecretResolver)" },
    { key: "token", label: "Inline Token (dev/test)", type: "text", placeholder: "ghp_...", help: "Inline token fallback � use secret ref in production" },
    { key: "branch", label: "Branch", type: "text", placeholder: "main", help: "Branch to clone (defaults to default branch)" },
    { key: "maxFileSize", label: "Max File Size (bytes)", type: "number", placeholder: "1048576", help: "Skip files larger than this (default 1MB)" },
  ],
  "web-crawl": [
    { key: "maxDepth", label: "Max Depth", type: "number", placeholder: "1", help: "Link hops from the seed URL (default 1)" },
    { key: "maxPages", label: "Max Pages", type: "number", placeholder: "50", help: "Hard page ceiling (default 50)" },
    { key: "includePattern", label: "Include Pattern (regex)", type: "text", placeholder: ".*\\.html$", help: "Only crawl URLs matching this pattern" },
    { key: "excludePattern", label: "Exclude Pattern (regex)", type: "text", placeholder: ".*\\/login.*", help: "Skip URLs matching this pattern" },
  ],
  s3: [
    { key: "accessKeySecret", label: "Access Key Secret Ref", type: "secret", placeholder: "aws-access-key" },
    { key: "secretKeySecret", label: "Secret Key Secret Ref", type: "secret", placeholder: "aws-secret-key" },
    { key: "accessKey", label: "Inline Access Key (dev/test)", type: "text", placeholder: "AKIA..." },
    { key: "secretKey", label: "Inline Secret Key (dev/test)", type: "text", placeholder: "..." },
    { key: "endpoint", label: "Custom Endpoint", type: "text", placeholder: "https://s3.amazonaws.com", help: "For R2, B2, MinIO, etc." },
    { key: "region", label: "Region", type: "text", placeholder: "us-east-1" },
  ],
  confluence: [
    { key: "apiTokenSecret", label: "API Token Secret Ref", type: "secret", placeholder: "confluence-token" },
    { key: "apiToken", label: "Inline Token (dev/test)", type: "text", placeholder: "ATATT3..." },
    { key: "email", label: "Account Email", type: "text", placeholder: "user@example.com", help: "Email for the API token" },
    { key: "maxPages", label: "Max Pages", type: "number", placeholder: "500", help: "Hard ceiling on pages ingested (default 500)" },
  ],
  jira: [
    { key: "apiTokenSecret", label: "API Token Secret Ref", type: "secret", placeholder: "jira-token" },
    { key: "apiToken", label: "Inline Token (dev/test)", type: "text", placeholder: "..." },
    { key: "email", label: "Account Email", type: "text", placeholder: "user@example.com" },
    { key: "jql", label: "JQL Query", type: "textarea", placeholder: "project = ENG ORDER BY updated DESC", help: "JQL to filter issues" },
    { key: "maxResults", label: "Max Results", type: "number", placeholder: "100" },
  ],
  "db-schema": [
    { key: "passwordSecret", label: "Password Secret Ref", type: "secret", placeholder: "db-password" },
    { key: "password", label: "Inline Password (dev/test)", type: "text", placeholder: "..." },
    { key: "username", label: "Username", type: "text", placeholder: "readonly_user" },
    { key: "driver", label: "JDBC Driver Class", type: "text", placeholder: "org.postgresql.Driver" },
    { key: "schemaPattern", label: "Schema Pattern", type: "text", placeholder: "public", help: "Only introspect schemas matching this pattern" },
    { key: "tablePattern", label: "Table Pattern", type: "text", placeholder: "%", help: "Only introspect tables matching this pattern" },
  ],
  notion: [
    { key: "tokenSecret", label: "Token Secret Ref", type: "secret", placeholder: "notion-token" },
    { key: "token", label: "Inline Token (dev/test)", type: "text", placeholder: "secret_..." },
    { key: "databaseId", label: "Database ID", type: "text", placeholder: "uuid", help: "Notion database to query" },
    { key: "maxPages", label: "Max Pages", type: "number", placeholder: "100" },
  ],
  manual: [],
}

/** Provider-specific config field definitions. */
export const PROVIDER_CONFIGS: Record<string, ConfigField[]> = {
  builtin: [],
  ragflow: [
    { key: "baseUrl", label: "RAGFlow Base URL", type: "text", required: true, placeholder: "http://ragflow:9380" },
    { key: "datasetId", label: "Dataset ID", type: "text", required: true, placeholder: "<ragflow dataset id>" },
    { key: "apiKeySecretRef", label: "API Key Secret Ref", type: "secret", required: true, placeholder: "ragflow-api-key", help: "Secret reference resolved to the bearer API key" },
    { key: "topKDefault", label: "Default Top-K", type: "number", placeholder: "8", default: 8 },
    { key: "similarityThreshold", label: "Similarity Threshold", type: "text", placeholder: "0.2", default: "0.2", help: "Minimum similarity in [0, 1]" },
  ],
  http: [
    { key: "endpoint", label: "Endpoint URL", type: "text", required: true, placeholder: "https://rag.acme.internal/search" },
    { key: "method", label: "HTTP Method", type: "text", placeholder: "POST", default: "POST" },
    { key: "authSecretRef", label: "Auth Secret Ref", type: "secret", required: true, placeholder: "acme-rag-token" },
    { key: "authHeader", label: "Auth Header", type: "text", placeholder: "Authorization", default: "Authorization" },
    { key: "authScheme", label: "Auth Scheme", type: "text", placeholder: "Bearer", default: "Bearer", help: "Empty string = raw token" },
    { key: "hitsPath", label: "Hits Path (JSONPath)", type: "text", required: true, placeholder: "$.results", help: "Root-relative path to the hits array" },
    { key: "passagePath", label: "Passage Path", type: "text", required: true, placeholder: "$.text", help: "Hit-relative path to the passage text" },
    { key: "sourceNamePath", label: "Source Name Path", type: "text", required: true, placeholder: "$.source" },
    { key: "locatorPath", label: "Locator Path", type: "text", required: true, placeholder: "$.url" },
    { key: "scorePath", label: "Score Path", type: "text", placeholder: "$.score", help: "Optional; 0.0 when absent" },
    { key: "timeoutMs", label: "Timeout (ms)", type: "number", placeholder: "5000", default: 5000 },
  ],
}

/** Human-readable descriptions for each retrieval provider, shown in the UI dropdown. */
export const PROVIDER_DESCRIPTIONS: Record<string, string> = {
  builtin: "Zero-config keyword search over local knowledge_chunks. No ingestion — connectors persist chunks directly.",
  ragflow: "RAGFlow vector retrieval with ingestion. Connectors push files to RAGFlow for parsing, chunking, and embedding.",
  http: "Generic HTTP retrieval proxy. Calls an external search endpoint and maps JSONPath hits. No ingestion.",
}

/** Providers that support ingestion (connector sync → provider.ingest). Sources are only available for these. */
export const INGESTION_PROVIDERS = new Set(["ragflow"])

/**
 * Render form fields for a given config schema.
 * Returns the current config values as a JSON string.
 */
export function ConfigFields({
  fields,
  values,
  onChange,
}: {
  fields: ConfigField[]
  values: Record<string, string>
  onChange: (values: Record<string, string>) => void
}) {
  if (fields.length === 0) {
    return <p className="text-sm text-muted-foreground">No configuration needed.</p>
  }

  return (
    <div className="space-y-3">
      {fields.map((field) => (
        <div key={field.key} className="space-y-1">
          <Label htmlFor={`cfg-${field.key}`}>
            {field.label}
            {field.required && <span className="text-destructive ml-1">*</span>}
          </Label>
          {field.type === "textarea" ? (
            <Textarea
              id={`cfg-${field.key}`}
              value={values[field.key] ?? ""}
              onChange={(e) => onChange({ ...values, [field.key]: e.target.value })}
              placeholder={field.placeholder}
              className="font-mono text-xs"
            />
          ) : (
            <Input
              id={`cfg-${field.key}`}
              type={field.type === "number" ? "number" : "text"}
              value={values[field.key] ?? ""}
              onChange={(e) => onChange({ ...values, [field.key]: e.target.value })}
              placeholder={field.placeholder}
            />
          )}
          {field.help && (
            <p className="text-xs text-muted-foreground">{field.help}</p>
          )}
        </div>
      ))}
    </div>
  )
}

/** Convert a values object to a JSON string, omitting empty values. */
export function valuesToJson(values: Record<string, string>): string {
  const filtered: Record<string, string | number> = {}
  for (const [k, v] of Object.entries(values)) {
    if (v !== "" && v != null) {
      const num = Number(v)
      filtered[k] = isNaN(num) || v.includes(".") === false && String(num) !== v ? v : num
    }
  }
  return Object.keys(filtered).length > 0 ? JSON.stringify(filtered, null, 2) : ""
}

/** Parse a JSON string back to a values object. */
export function jsonToValues(json: string | null | undefined): Record<string, string> {
  if (!json) return {}
  try {
    const parsed = JSON.parse(json)
    const result: Record<string, string> = {}
    for (const [k, v] of Object.entries(parsed)) {
      result[k] = String(v)
    }
    return result
  } catch {
    return {}
  }
}