# React TypeScript Standards

> **Applies to**: `**/*.tsx`, `**/*.ts`

## Project Structure

```
src/
├── components/           # Reusable UI components
│   ├── ui/              # Base components (Button, Input, etc.)
│   └── contact/         # Feature-specific components
│       ├── ContactList.tsx
│       ├── ContactForm.tsx
│       └── ContactCard.tsx
├── hooks/               # Custom React hooks
│   ├── useContacts.ts
│   └── useGroups.ts
├── api/                 # API client functions
│   ├── contacts.ts
│   └── groups.ts
├── lib/                 # Utilities
│   ├── utils.ts
│   └── api.ts
└── types/              # TypeScript interfaces
    └── index.ts
```

## Component Patterns

### Functional Components Only

```tsx
// Good - functional component
export function ContactCard({ contact, onEdit, onDelete }: ContactCardProps) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{contact.firstName} {contact.lastName}</CardTitle>
      </CardHeader>
      <CardContent>
        <p>{contact.email}</p>
      </CardContent>
      <CardFooter className="flex gap-2">
        <Button variant="outline" onClick={() => onEdit(contact)}>Edit</Button>
        <Button variant="destructive" onClick={() => onDelete(contact.id)}>Delete</Button>
      </CardFooter>
    </Card>
  );
}

// Define props interface
interface ContactCardProps {
  contact: Contact;
  onEdit: (contact: Contact) => void;
  onDelete: (id: string) => void;
}
```

### Use shadcn/ui Components

```tsx
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardContent, CardFooter } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
```

## Data Fetching with TanStack Query

### Query Hooks

```tsx
// hooks/useContacts.ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { contactsApi } from '@/api/contacts';

export function useContacts() {
  return useQuery({
    queryKey: ['contacts'],
    queryFn: contactsApi.getAll,
  });
}

export function useContact(id: string) {
  return useQuery({
    queryKey: ['contacts', id],
    queryFn: () => contactsApi.getById(id),
    enabled: !!id,
  });
}

export function useCreateContact() {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: contactsApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contacts'] });
    },
  });
}

export function useDeleteContact() {
  const queryClient = useQueryClient();
  
  return useMutation({
    mutationFn: contactsApi.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['contacts'] });
    },
  });
}
```

### Using Queries in Components

```tsx
export function ContactList() {
  const { data: contacts, isLoading, error } = useContacts();
  const deleteMutation = useDeleteContact();
  
  if (isLoading) return <LoadingSpinner />;
  if (error) return <ErrorMessage error={error} />;
  
  return (
    <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
      {contacts?.map((contact) => (
        <ContactCard
          key={contact.id}
          contact={contact}
          onDelete={(id) => deleteMutation.mutate(id)}
        />
      ))}
    </div>
  );
}
```

## TypeScript Best Practices

### Define Interfaces for All Data

```typescript
// types/index.ts
export interface Contact {
  id: string;
  email: string;
  firstName: string;
  lastName: string | null;
  groupId: string | null;
  groupName: string | null;
  createdAt: string;
}

export interface CreateContactRequest {
  email: string;
  firstName: string;
  lastName?: string;
  groupId?: string;
}

export interface UpdateContactRequest {
  email?: string;
  firstName?: string;
  lastName?: string;
  groupId?: string | null;
}
```

### Use Strict Null Checks

```tsx
// Good - explicit null handling
function ContactGroupBadge({ groupName }: { groupName: string | null }) {
  if (!groupName) return null;
  return <Badge>{groupName}</Badge>;
}
```

## Styling with Tailwind CSS

```tsx
// Use Tailwind classes
<div className="flex flex-col gap-4 p-4">
  <h1 className="text-2xl font-bold">Contacts</h1>
  <div className="grid gap-4 md:grid-cols-2">
    {/* content */}
  </div>
</div>

// Use cn() for conditional classes
import { cn } from '@/lib/utils';

<Button className={cn(
  "w-full",
  isLoading && "opacity-50 cursor-not-allowed"
)}>
  {isLoading ? 'Saving...' : 'Save'}
</Button>
```

## Form Handling

```tsx
import { useState } from 'react';

export function ContactForm({ onSubmit, initialData }: ContactFormProps) {
  const [email, setEmail] = useState(initialData?.email ?? '');
  const [firstName, setFirstName] = useState(initialData?.firstName ?? '');
  const [lastName, setLastName] = useState(initialData?.lastName ?? '');
  
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({ email, firstName, lastName: lastName || undefined });
  };
  
  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>
      {/* ... more fields */}
      <Button type="submit">Save</Button>
    </form>
  );
}
```
