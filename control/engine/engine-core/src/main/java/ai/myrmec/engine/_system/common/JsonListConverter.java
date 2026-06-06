package ai.myrmec.engine._system.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * JPA AttributeConverter for mapping List<String> to JSON string in database.
 * This provides database-agnostic JSON storage (works with PostgreSQL jsonb, H2 clob, etc.).
 */
@Converter
@Slf4j
public class JsonListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize list to JSON", e);
            throw new IllegalArgumentException("Failed to serialize list to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || dbData.equals("[]")) {
            return new java.util.ArrayList<>();
        }
        try {
            return new java.util.ArrayList<>(OBJECT_MAPPER.readValue(dbData, LIST_TYPE));
        } catch (IOException e) {
            log.error("Failed to deserialize JSON to list", e);
            throw new IllegalArgumentException("Failed to deserialize list from JSON", e);
        }
    }
}
