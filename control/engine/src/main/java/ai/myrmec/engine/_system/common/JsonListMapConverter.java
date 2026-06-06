package ai.myrmec.engine._system.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * JPA AttributeConverter for mapping List<Map<String, Object>> to JSON string in database.
 * Used for complex nested structures like workflow steps.
 * This provides database-agnostic JSON storage (works with PostgreSQL jsonb, H2 clob, etc.).
 */
@Converter
@Slf4j
public class JsonListMapConverter implements AttributeConverter<List<Map<String, Object>>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Map<String, Object>> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list of maps to JSON", e);
            throw new IllegalArgumentException("Failed to serialize list of maps to JSON", e);
        }
    }

    @Override
    public List<Map<String, Object>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || dbData.equals("[]")) {
            return new java.util.ArrayList<>();
        }
        try {
            return new java.util.ArrayList<>(OBJECT_MAPPER.readValue(dbData, LIST_MAP_TYPE));
        } catch (IOException e) {
            log.error("Failed to deserialize JSON to list of maps", e);
            throw new IllegalArgumentException("Failed to deserialize list of maps from JSON", e);
        }
    }
}
