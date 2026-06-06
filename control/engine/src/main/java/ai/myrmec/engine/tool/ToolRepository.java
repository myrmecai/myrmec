package ai.myrmec.engine.tool;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolRepository extends JpaRepository<Tool, String> {
    
    List<Tool> findByStatus(ToolStatus status);
    
    List<Tool> findByToolType(ToolType toolType);
    
    List<Tool> findByStatusAndToolType(ToolStatus status, ToolType toolType);
    
    List<Tool> findByIsSystemTrue();
    
    boolean existsByCode(String code);
}
