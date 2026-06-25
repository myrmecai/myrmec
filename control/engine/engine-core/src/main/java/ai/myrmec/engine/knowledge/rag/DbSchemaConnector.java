package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "db-schema"}) that reads a
 * relational database's structure through JDBC {@link DatabaseMetaData} and
 * emits one data-dictionary chunk per table/view (#25).
 *
 * <p>The source {@code uri} is a JDBC URL (e.g.
 * {@code jdbc:postgresql://host:5432/sales}). Authentication uses a username
 * plus a password resolved via the {@link ConnectorContext} secret resolver
 * ({@code passwordSecret}) or an inline {@code password} fallback. The
 * connector never runs DML/queries against user data — it only walks the
 * driver's metadata catalog, so it is read-only and safe to point at a
 * production replica.</p>
 *
 * <p><strong>Config JSON</strong> (all optional) read from
 * {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "username": "readonly",
 *   "passwordSecret": "sales-db-password",   // resolved via ConnectorContext
 *   "password": "…",                         // inline fallback (dev)
 *   "catalog": "sales",                       // JDBC catalog filter (optional)
 *   "schemas": ["public", "billing"],         // if set, only these schemas
 *   "includeTables": ["cust*", "order*"],     // globs on the table name
 *   "excludeTables": ["*_audit", "flyway_*"], // globs always skipped
 *   "includeViews": true                       // index views too (default true)
 * }
 * }</pre>
 *
 * <p>Each emitted chunk's locator is {@code schema.table} (or {@code table}
 * when the schema is null), and the content is a compact data dictionary:
 * the table type, every column with its SQL type / nullability, the primary
 * key, and outgoing foreign keys. This is what NL&rarr;SQL and data-governance
 * assistants retrieve to ground answers about the data model.</p>
 *
 * <p>A per-table metadata read failure is recorded in
 * {@link SyncResult#errors()} (downgrading the run to
 * {@link SyncResult.Status#PARTIAL}); a connection/auth failure is
 * non-recoverable and throws {@link ConnectorException}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DbSchemaConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "db-schema";

    /** Table types we surface as knowledge; everything else is ignored. */
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    /**
     * Schemas that are part of the engine itself and never carry user data.
     * Skipped unless the operator names them explicitly in {@code schemas}.
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST", "SYS", "SYSTEM_LOBS");

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        DbSchemaConfig config = parseConfig(locator.config().get("raw"));
        String password = resolvePassword(config, context);

        List<String> errors = new ArrayList<>();
        long emitted = 0;
        try (Connection connection = openConnection(locator.uri(), config.username(), password)) {
            connection.setReadOnly(true);
            DatabaseMetaData meta = connection.getMetaData();
            List<TableRef> tables = listTables(meta, config, errors);
            for (TableRef table : tables) {
                EmittedChunk chunk = describeTable(meta, table, errors);
                if (chunk != null) {
                    context.chunkSink().accept(chunk);
                    emitted++;
                }
            }
            log.info("db-schema sync emitted {} table chunk(s) from {} ({} error(s))",
                    emitted, redactUrl(locator.uri()), errors.size());
        } catch (SQLException e) {
            throw new ConnectorException(
                    "Database connection failed for " + redactUrl(locator.uri()) + ": " + e.getMessage(), e);
        }

        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- connect -------------------------------------------------------------

    private Connection openConnection(String url, String username, String password) throws SQLException {
        if (username != null && !username.isBlank()) {
            return DriverManager.getConnection(url, username, password);
        }
        // Some embedded/SSO drivers carry credentials in the URL itself.
        return DriverManager.getConnection(url);
    }

    // --- discovery -----------------------------------------------------------

    private List<TableRef> listTables(DatabaseMetaData meta, DbSchemaConfig config, List<String> errors) {
        Set<String> wantedSchemas = lowerSet(config.schemas());
        List<Pattern> includes = compileGlobs(config.includeTables());
        List<Pattern> excludes = compileGlobs(config.excludeTables());
        boolean includeViews = config.includeViews() == null || config.includeViews();

        List<TableRef> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(blankToNull(config.catalog()), null, "%", TABLE_TYPES)) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                String name = rs.getString("TABLE_NAME");
                String tableType = rs.getString("TABLE_TYPE");
                if (name == null) {
                    continue;
                }
                if (!includeViews && "VIEW".equalsIgnoreCase(tableType)) {
                    continue;
                }
                if (!schemaAllowed(schema, wantedSchemas)) {
                    continue;
                }
                if (!nameAllowed(name, includes, excludes)) {
                    continue;
                }
                tables.add(new TableRef(rs.getString("TABLE_CAT"), schema, name, tableType));
            }
        } catch (SQLException e) {
            errors.add("table enumeration failed: " + e.getMessage());
        }
        return tables;
    }

    private boolean schemaAllowed(String schema, Set<String> wantedSchemas) {
        if (!wantedSchemas.isEmpty()) {
            return schema != null && wantedSchemas.contains(schema.toLowerCase(Locale.ROOT));
        }
        // Default: every user schema, but never the engine's own metadata schemas.
        if (schema == null) {
            return true;
        }
        String upper = schema.toUpperCase(Locale.ROOT);
        return !SYSTEM_SCHEMAS.contains(upper) && !upper.startsWith("PG_");
    }

    private static boolean nameAllowed(String name, List<Pattern> includes, List<Pattern> excludes) {
        for (Pattern exclude : excludes) {
            if (exclude.matcher(name).matches()) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (Pattern include : includes) {
            if (include.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    // --- describe ------------------------------------------------------------

    private EmittedChunk describeTable(DatabaseMetaData meta, TableRef table, List<String> errors) {
        try {
            List<String> columns = readColumns(meta, table);
            if (columns.isEmpty()) {
                return null; // no readable columns — nothing to ground on
            }
            List<String> primaryKey = readPrimaryKey(meta, table);
            List<String> foreignKeys = readForeignKeys(meta, table);
            String content = renderDictionary(table, columns, primaryKey, foreignKeys);
            return new EmittedChunk(table.locator(), content, table.metadata(columns.size()));
        } catch (SQLException e) {
            errors.add("skipped (metadata read error): " + table.locator() + " — " + e.getMessage());
            return null;
        }
    }

    private List<String> readColumns(DatabaseMetaData meta, TableRef table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(table.catalog(), table.schema(), table.name(), "%")) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                StringBuilder line = new StringBuilder("  - ").append(columnName).append(' ').append(typeName);
                if (size > 0 && isSizedType(typeName)) {
                    line.append('(').append(size).append(')');
                }
                line.append(nullable ? " NULL" : " NOT NULL");
                columns.add(line.toString());
            }
        }
        return columns;
    }

    private List<String> readPrimaryKey(DatabaseMetaData meta, TableRef table) throws SQLException {
        List<String> pk = new ArrayList<>();
        try (ResultSet rs = meta.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (rs.next()) {
                pk.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pk;
    }

    private List<String> readForeignKeys(DatabaseMetaData meta, TableRef table) throws SQLException {
        List<String> fks = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(table.catalog(), table.schema(), table.name())) {
            while (rs.next()) {
                String fkColumn = rs.getString("FKCOLUMN_NAME");
                String pkSchema = rs.getString("PKTABLE_SCHEM");
                String pkTable = rs.getString("PKTABLE_NAME");
                String pkColumn = rs.getString("PKCOLUMN_NAME");
                String target = (pkSchema == null ? "" : pkSchema + ".") + pkTable + "(" + pkColumn + ")";
                fks.add("  - " + fkColumn + " -> " + target);
            }
        }
        return fks;
    }

    private String renderDictionary(TableRef table, List<String> columns,
                                    List<String> primaryKey, List<String> foreignKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append(table.kindLabel()).append(": ").append(table.locator()).append('\n');
        sb.append("Columns:\n");
        for (String column : columns) {
            sb.append(column).append('\n');
        }
        if (!primaryKey.isEmpty()) {
            sb.append("Primary key: ").append(String.join(", ", primaryKey)).append('\n');
        }
        if (!foreignKeys.isEmpty()) {
            sb.append("Foreign keys:\n");
            for (String fk : foreignKeys) {
                sb.append(fk).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    // --- helpers -------------------------------------------------------------

    private DbSchemaConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return DbSchemaConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, DbSchemaConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid db-schema source config JSON: " + e.getMessage(), e);
        }
    }

    private String resolvePassword(DbSchemaConfig config, ConnectorContext context) {
        if (config.passwordSecret() != null && !config.passwordSecret().isBlank()) {
            String resolved = context.resolveSecret(config.passwordSecret());
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return config.password();
    }

    /** Compile shell-style globs ({@code *}, {@code ?}) into case-insensitive regexes. */
    private static List<Pattern> compileGlobs(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>(globs.size());
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            patterns.add(Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private static Set<String> lowerSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        List<String> lowered = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                lowered.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(lowered);
    }

    /** Length only adds meaning to character/binary types; suppress it for numerics/dates. */
    private static boolean isSizedType(String typeName) {
        if (typeName == null) {
            return false;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        return upper.contains("CHAR") || upper.contains("BINARY") || upper.contains("VARCHAR");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Strip any inline credentials from a JDBC URL before it reaches a log line. */
    private static String redactUrl(String url) {
        if (url == null) {
            return "<null>";
        }
        return url.replaceAll("(?i)(password|user)=([^;&]*)", "$1=***");
    }

    private record TableRef(String catalog, String schema, String name, String tableType) {

        String locator() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }

        String kindLabel() {
            return "VIEW".equalsIgnoreCase(tableType) ? "View" : "Table";
        }

        Map<String, String> metadata(int columnCount) {
            Map<String, String> metadata = new LinkedHashMap<>();
            if (schema != null && !schema.isBlank()) {
                metadata.put("schema", schema);
            }
            metadata.put("table", name);
            metadata.put("type", tableType == null ? "TABLE" : tableType);
            metadata.put("columnCount", Integer.toString(columnCount));
            return metadata;
        }
    }

    /**
     * Connector-owned config schema deserialised from
     * {@code knowledge_sources.config_json}. Unknown keys are ignored so the
     * schema can grow without breaking older sources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DbSchemaConfig(
            String username,
            String password,
            String passwordSecret,
            String catalog,
            List<String> schemas,
            List<String> includeTables,
            List<String> excludeTables,
            Boolean includeViews) {

        static final DbSchemaConfig EMPTY =
                new DbSchemaConfig(null, null, null, null, null, null, null, null);
    }
}
