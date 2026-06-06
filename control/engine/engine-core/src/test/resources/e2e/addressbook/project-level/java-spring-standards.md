# Java Spring Boot Standards

> **Applies to**: `**/*.java`

## Project Structure

```
src/main/java/com/example/
├── contact/              # Feature package
│   ├── Contact.java      # Entity
│   ├── ContactRepository.java
│   ├── ContactService.java
│   ├── ContactController.java
│   └── dto/
│       ├── ContactRequest.java
│       └── ContactResponse.java
├── group/               # Another feature
└── common/              # Shared utilities
```

## Entity Design

### Use JPA Annotations Correctly

```java
@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class Contact {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "email", nullable = false, unique = true)
    private String email;
    
    @Column(name = "first_name", nullable = false)
    private String firstName;
    
    @Column(name = "last_name")
    private String lastName;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private Group group;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
```

## DTOs - Use Records

```java
// Request DTO
public record ContactRequest(
    @NotBlank String email,
    @NotBlank String firstName,
    String lastName,
    UUID groupId
) {}

// Response DTO
public record ContactResponse(
    UUID id,
    String email,
    String firstName,
    String lastName,
    String groupName,
    Instant createdAt
) {
    public static ContactResponse from(Contact contact) {
        return new ContactResponse(
            contact.getId(),
            contact.getEmail(),
            contact.getFirstName(),
            contact.getLastName(),
            contact.getGroup() != null ? contact.getGroup().getName() : null,
            contact.getCreatedAt()
        );
    }
}
```

## Service Layer

### Constructor Injection (No @Autowired on fields)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ContactService {
    
    private final ContactRepository contactRepository;
    private final GroupRepository groupRepository;
    
    @Transactional
    public Contact create(ContactRequest request, UUID createdBy) {
        log.debug("Creating contact: {}", request.email());
        
        if (contactRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Contact", "email", request.email());
        }
        
        Contact contact = new Contact();
        contact.setEmail(request.email());
        contact.setFirstName(request.firstName());
        contact.setLastName(request.lastName());
        
        if (request.groupId() != null) {
            Group group = groupRepository.findById(request.groupId())
                .orElseThrow(() -> new ResourceNotFoundException("Group", request.groupId()));
            contact.setGroup(group);
        }
        
        return contactRepository.save(contact);
    }
    
    @Transactional(readOnly = true)
    public List<Contact> findAll() {
        return contactRepository.findAll();
    }
}
```

## Repository Layer

```java
public interface ContactRepository extends JpaRepository<Contact, UUID> {
    
    boolean existsByEmail(String email);
    
    Optional<Contact> findByEmail(String email);
    
    List<Contact> findByGroupId(UUID groupId);
    
    @Query("SELECT c FROM Contact c WHERE c.lastName LIKE %:name% OR c.firstName LIKE %:name%")
    List<Contact> searchByName(@Param("name") String name);
}
```

## Exception Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("RESOURCE_NOT_FOUND", ex.getMessage()));
    }
    
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(409)
            .body(new ErrorResponse("DUPLICATE_RESOURCE", ex.getMessage()));
    }
}
```
