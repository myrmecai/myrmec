package ai.myrmec.engine._system.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

/**
 * JPA AttributeConverter for mapping Map<String, Object> to JSON string in database.
 * This provides database-agnostic JSON storage (works with PostgreSQL jsonb, H2 clob, etc.).
 */
@Converter
@Slf4j
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize map to JSON", e);
            throw new IllegalArgumentException("Failed to serialize metadata to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || dbData.equals("{}")) {
            return new java.util.HashMap<>();
        }
        try {
            return new java.util.HashMap<>(OBJECT_MAPPER.readValue(dbData, MAP_TYPE));
        } catch (IOException e) {
            log.error("Failed to deserialize JSON to map", e);
            throw new IllegalArgumentException("Failed to deserialize metadata from JSON", e);
        }
    }
}
