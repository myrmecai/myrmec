---
description: "Use when writing React Native/TypeScript code in the control UI. Covers component patterns, state management, and styling."
applyTo: "control/ui/**/*.{ts,tsx}"
---
# UI Coding Standards (React Native/TypeScript)

## Component Structure

- Use functional components with hooks
- Props interface named `{ComponentName}Props`
- Export component as default, types as named exports

```tsx
interface AgentCardProps {
  agent: Agent;
  onSelect: (id: string) => void;
}

export default function AgentCard({ agent, onSelect }: AgentCardProps) {
  // ...
}
```

## State Management

- Local state: `useState` for UI state
- Server state: React Query for API data
- Form state: React Hook Form with Zod validation

## API Calls

- Use generated API client from OpenAPI spec
- Handle loading, error, and success states
- Show user-friendly error messages

## Styling

- Use Tailwind/NativeWind classes
- Consistent spacing: 4, 8, 12, 16, 24, 32
- Colors from theme, never hardcoded hex values

## TypeScript

- Strict mode enabled
- No `any` type - use `unknown` if type is truly unknown
- Prefer interfaces over type aliases for objects

## Error Handling

The API returns consistent error responses (see project instructions). Handle them uniformly.

### API Error Interface

```typescript
interface ApiError {
  errorCode: string;
  message: string;
  details?: ValidationError[] | ResourceDetails | ResourceInUse[];
}

interface ValidationError {
  field: string;
  errorCode: string;
  message: string;
}

interface ResourceDetails {
  resourceType: string;
  identifier: string;
}

interface ResourceInUse {
  resourceType: string;
  blocking: boolean;
  count: number;
}
```

### Error Display Guidelines

- Show `message` field for user-friendly text
- For `VALIDATION_ERROR`, show field-specific errors next to form inputs
- For `RESOURCE_NOT_FOUND`, navigate to list view or show "not found" state
- For `RESOURCE_IN_USE`, explain what's blocking the operation
- For `UNAUTHORIZED`, redirect to login
- For `FORBIDDEN`, show permission denied message
- For `INTERNAL_ERROR`, show generic "Something went wrong" with retry option

### Form Validation Errors

```tsx
// Example: Display field errors from API response
function FormWithApiErrors({ apiError }: { apiError?: ApiError }) {
  const fieldErrors = useMemo(() => {
    if (apiError?.errorCode !== 'VALIDATION_ERROR') return {};
    const errors: Record<string, string> = {};
    for (const detail of apiError.details as ValidationError[]) {
      errors[detail.field] = detail.message;
    }
    return errors;
  }, [apiError]);

  return (
    <form>
      <Input name="code" />
      {fieldErrors.code && (
        <span className="text-destructive text-sm">{fieldErrors.code}</span>
      )}
    </form>
  );
}
```

### Toast Notifications

- Success: Brief confirmation ("Project created")
- Error: Show `message` from API, with action to retry if applicable
- Never show raw error codes to users in toasts

### Localization Readiness

- Error codes (`errorCode`, field `errorCode`) can be used as i18n keys
- Fall back to `message` field if no translation exists
- Store translated messages in a lookup table keyed by error code
