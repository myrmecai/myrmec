package ai.myrmec.engine.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupRepository extends JpaRepository<Group, UUID> {

    Optional<Group> findByName(String name);

    boolean existsByName(String name);

    /**
     * Return the given group plus all of its ancestors (walking
     * {@code parent_group_id} upward). v1 is flat so this collapses to one row,
     * but the recursive CTE is correct from day one.
     *
     * <p>Returns IDs as strings because H2 surfaces native-query UUID columns as
     * {@code byte[]}, which Spring Data cannot auto-convert to {@link UUID}.
     * Use {@link #findAncestorIds(UUID)} to get the parsed list.
     */
    @Query(value = """
        WITH RECURSIVE ancestors(id, parent_group_id) AS (
            SELECT id, parent_group_id FROM groups WHERE id = :groupId
            UNION ALL
            SELECT g.id, g.parent_group_id
            FROM groups g
            JOIN ancestors a ON g.id = a.parent_group_id
        )
        SELECT CAST(id AS VARCHAR(36)) FROM ancestors
        """, nativeQuery = true)
    List<String> findAncestorIdStrings(@Param("groupId") UUID groupId);

    default List<UUID> findAncestorIds(UUID groupId) {
        return findAncestorIdStrings(groupId).stream()
                .map(UUID::fromString)
                .toList();
    }
}
