# Address Book Application Architecture

## Overview

A contact management application with Java/Spring Boot backend and React frontend.

## Domain Model

```
┌─────────────┐       ┌─────────────┐
│   Contact   │──────>│    Group    │
├─────────────┤  N:1  ├─────────────┤
│ id: UUID    │       │ id: UUID    │
│ email       │       │ name        │
│ firstName   │       │ description │
│ lastName    │       │ createdAt   │
│ phone?      │       └─────────────┘
│ address?    │
│ notes?      │
│ groupId?    │
│ createdAt   │
│ updatedAt   │
└─────────────┘
```

### Contact Entity

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | UUID | Auto | Primary key |
| email | String | Yes | Unique, validated format |
| firstName | String | Yes | Contact's first name |
| lastName | String | No | Contact's last name |
| phone | String | No | Phone number |
| address | String | No | Full address |
| notes | String | No | Free-form notes |
| groupId | UUID | No | FK to Group |
| createdAt | Instant | Auto | Creation timestamp |
| updatedAt | Instant | Auto | Last update timestamp |

### Group Entity

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | UUID | Auto | Primary key |
| name | String | Yes | Unique group name |
| description | String | No | Group description |
| createdAt | Instant | Auto | Creation timestamp |

## API Endpoints

### Contacts

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/v1/contacts | List all contacts |
| GET | /api/v1/contacts/{id} | Get contact by ID |
| POST | /api/v1/contacts | Create contact |
| PUT | /api/v1/contacts/{id} | Update contact |
| DELETE | /api/v1/contacts/{id} | Delete contact |
| GET | /api/v1/contacts/search?q= | Search contacts |

### Groups

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/v1/groups | List all groups |
| GET | /api/v1/groups/{id} | Get group by ID |
| POST | /api/v1/groups | Create group |
| PUT | /api/v1/groups/{id} | Update group |
| DELETE | /api/v1/groups/{id} | Delete group |
| GET | /api/v1/groups/{id}/contacts | List contacts in group |

## Backend Package Structure

```
com.example.addressbook/
├── AddressBookApplication.java
├── contact/
│   ├── Contact.java              # Entity
│   ├── ContactRepository.java    # Data access
│   ├── ContactService.java       # Business logic
│   ├── ContactController.java    # REST API
│   └── dto/
│       ├── ContactRequest.java   # Create/Update DTO
│       └── ContactResponse.java  # Response DTO
├── group/
│   ├── Group.java
│   ├── GroupRepository.java
│   ├── GroupService.java
│   ├── GroupController.java
│   └── dto/
│       ├── GroupRequest.java
│       └── GroupResponse.java
└── common/
    ├── exception/
    │   ├── GlobalExceptionHandler.java
    │   ├── ResourceNotFoundException.java
    │   └── DuplicateResourceException.java
    └── config/
        └── SecurityConfig.java
```

## Frontend Component Hierarchy

```
App
├── Layout
│   ├── Sidebar
│   │   ├── GroupList
│   │   └── GroupCreateButton
│   └── MainContent
│       └── Routes
│           ├── ContactListPage
│           │   ├── SearchBar
│           │   ├── ContactList
│           │   │   └── ContactCard[]
│           │   └── ContactCreateDialog
│           ├── ContactDetailPage
│           │   ├── ContactInfo
│           │   └── ContactEditDialog
│           └── GroupDetailPage
│               ├── GroupInfo
│               └── ContactList (filtered)
└── Providers
    └── QueryClientProvider
```

## Data Flow

### Create Contact

```
User fills form → ContactForm.onSubmit()
    → useCreateContact.mutate(data)
        → contactsApi.create(data)
            → POST /api/v1/contacts
                → ContactController.create()
                    → ContactService.create()
                        → ContactRepository.save()
        ← Contact (created)
    → invalidateQueries(['contacts'])
    → UI updates automatically
```

### List Contacts with Group Filter

```
User clicks group → navigate('/groups/{id}')
    → useContacts(groupId)
        → GET /api/v1/groups/{id}/contacts
            → GroupController.listContacts()
                → ContactService.findByGroupId()
        ← Contact[]
    → ContactList renders filtered contacts
```

## Tech Stack

### Backend
- Java 21
- Spring Boot 3.x
- Spring Data JPA
- PostgreSQL (prod) / H2 (test)
- Liquibase migrations

### Frontend
- React 18
- TypeScript
- TanStack Router
- TanStack Query
- shadcn/ui + Tailwind CSS
- Vite
