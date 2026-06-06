package ai.myrmec.engine.group;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.group.dto.CreateGroupRequest;
import ai.myrmec.engine.group.dto.UpdateGroupRequest;
import ai.myrmec.engine.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for {@link Group} management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupService {

    private final GroupRepository groupRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<Group> findAll() {
        return groupRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Group findById(UUID id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Group", "id", id.toString()));
    }

    @Transactional
    public Group create(CreateGroupRequest request) {
        String name = request.getName().trim();
        if (groupRepository.existsByName(name)) {
            throw new BadRequestException("Group with name '" + name + "' already exists");
        }
        if (request.getParentGroupId() != null
                && !groupRepository.existsById(request.getParentGroupId())) {
            throw ResourceNotFoundException.of("Group", "id", request.getParentGroupId().toString());
        }

        Group group = new Group();
        group.setName(name);
        group.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        group.setParentGroupId(request.getParentGroupId());
        group = groupRepository.save(group);
        log.info("Created group: {} ({})", group.getName(), group.getId());
        return group;
    }

    @Transactional
    public Group update(UUID id, UpdateGroupRequest request) {
        Group group = findById(id);

        if (request.getName() != null) {
            String trimmed = request.getName().trim();
            if (trimmed.isEmpty()) {
                throw new BadRequestException("Group name cannot be blank");
            }
            if (!trimmed.equals(group.getName()) && groupRepository.existsByName(trimmed)) {
                throw new BadRequestException("Group with name '" + trimmed + "' already exists");
            }
            group.setName(trimmed);
        }
        if (request.getDescription() != null) {
            String d = request.getDescription().trim();
            group.setDescription(d.isEmpty() ? null : d);
        }
        group = groupRepository.save(group);
        log.info("Updated group: {} ({})", group.getName(), id);
        return group;
    }

    @Transactional
    public void delete(UUID id) {
        Group group = findById(id);
        if (Group.DEFAULT_GROUP_ID.equals(id)) {
            throw new BadRequestException("The Default group cannot be deleted");
        }
        long projectCount = projectRepository.countByGroupId(id);
        if (projectCount > 0) {
            throw ResourceInUseException.blockedBy("Project", (int) projectCount);
        }
        groupRepository.delete(group);
        log.info("Deleted group: {} ({})", group.getName(), id);
    }
}
