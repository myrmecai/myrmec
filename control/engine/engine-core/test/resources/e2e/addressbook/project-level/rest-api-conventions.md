# REST API Conventions

> **Applies to**: `**/controller/*.java`, `**/api/*.ts`

## URL Structure

### Resource Naming
- Use plural nouns: `/contacts`, `/groups`
- Use lowercase with hyphens: `/contact-groups`
- Nest related resources: `/groups/{groupId}/contacts`

### HTTP Methods
| Method | Usage | Example |
|--------|-------|---------|
| GET | Read resource(s) | `GET /contacts` |
| POST | Create resource | `POST /contacts` |
| PUT | Full update | `PUT /contacts/{id}` |
| PATCH | Partial update | `PATCH /contacts/{id}` |
| DELETE | Remove resource | `DELETE /contacts/{id}` |

## Request/Response Format

### Content Type
- Always use `Content-Type: application/json`
- Request bodies: camelCase JSON
- Response bodies: camelCase JSON

### Success Responses

```json
// GET /contacts - List
{
  "data": [
    { "id": "uuid", "email": "john@example.com", "firstName": "John" }
  ],
  "total": 100,
  "page": 1,
  "pageSize": 20
}

// GET /contacts/{id} - Single
{
  "id": "uuid",
  "email": "john@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "createdAt": "2024-01-15T10:30:00Z"
}

// POST /contacts - Create (return created resource)
{
  "id": "new-uuid",
  "email": "jane@example.com",
  "firstName": "Jane"
}

// DELETE - Return 204 No Content
```

## Error Response Format

All errors must follow this structure:

```json
{
  "errorCode": "ERROR_CODE",
  "message": "Human-readable message",
  "details": { }
}
```

### Standard Error Codes

| Code | HTTP Status | When |
|------|-------------|------|
| `VALIDATION_ERROR` | 400 | Invalid input |
| `UNAUTHORIZED` | 401 | Missing/invalid auth |
| `FORBIDDEN` | 403 | Insufficient permissions |
| `RESOURCE_NOT_FOUND` | 404 | Entity doesn't exist |
| `DUPLICATE_RESOURCE` | 409 | Unique constraint violation |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

### Validation Error Details

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "One or more validation errors occurred.",
  "details": [
    { "field": "email", "errorCode": "REQUIRED", "message": "Email is required" },
    { "field": "email", "errorCode": "INVALID_FORMAT", "message": "Invalid email format" }
  ]
}
```

## Pagination

### Request Parameters
```
GET /contacts?page=1&pageSize=20&sort=lastName,asc
```

### Response Structure
```json
{
  "data": [...],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalPages": 5,
    "totalItems": 100
  }
}
```

## Filtering & Search

```
GET /contacts?groupId=uuid&search=john
GET /contacts?createdAfter=2024-01-01&createdBefore=2024-12-31
```

## Java Controller Example

```java
@RestController
@RequestMapping("/api/v1/contacts")
@RequiredArgsConstructor
public class ContactController {
    
    private final ContactService contactService;
    
    @GetMapping
    public List<ContactResponse> list() {
        return contactService.findAll().stream()
            .map(ContactResponse::from)
            .toList();
    }
    
    @GetMapping("/{id}")
    public ContactResponse get(@PathVariable UUID id) {
        return ContactResponse.from(contactService.findById(id));
    }
    
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(@Valid @RequestBody ContactRequest request) {
        Contact created = contactService.create(request);
        return ContactResponse.from(created);
    }
    
    @PutMapping("/{id}")
    public ContactResponse update(@PathVariable UUID id, 
                                  @Valid @RequestBody ContactRequest request) {
        Contact updated = contactService.update(id, request);
        return ContactResponse.from(updated);
    }
    
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        contactService.delete(id);
    }
}
```

## TypeScript API Client Example

```typescript
// api/contacts.ts
const BASE_URL = '/api/v1/contacts';

export const contactsApi = {
  getAll: async (): Promise<Contact[]> => {
    const response = await fetch(BASE_URL);
    if (!response.ok) throw await parseError(response);
    return response.json();
  },
  
  getById: async (id: string): Promise<Contact> => {
    const response = await fetch(`${BASE_URL}/${id}`);
    if (!response.ok) throw await parseError(response);
    return response.json();
  },
  
  create: async (data: CreateContactRequest): Promise<Contact> => {
    const response = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    });
    if (!response.ok) throw await parseError(response);
    return response.json();
  },
  
  delete: async (id: string): Promise<void> => {
    const response = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' });
    if (!response.ok) throw await parseError(response);
  },
};

async function parseError(response: Response): Promise<ApiError> {
  const body = await response.json();
  return new ApiError(body.errorCode, body.message, body.details);
}
```
