package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link DbSchemaConnector} (#25). Builds a throwaway H2
 * in-memory database with a couple of related tables and a view, then drives a
 * sync through {@link ConnectorDispatcher} pointed at that database's JDBC URL
 * so the test is hermetic (no external DB). Verifies the data-dictionary
 * emission (columns / PK / FK), schema + table filters, view handling, and
 * connection-failure handling.
 */
@Transactional
class DbSchemaConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    private String sourceDbUrl;
    private Connection keepAlive;

    @BeforeEach
    void createSourceDatabase() throws SQLException {
        // Distinct named mem DB so we never touch the app's own H2 instance.
        sourceDbUrl = "jdbc:h2:mem:dbschema_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        // Hold one connection open so the mem DB survives between the seed and
        // the connector's own (separate) connection.
        keepAlive = DriverManager.getConnection(sourceDbUrl);
        try (Statement st = keepAlive.createStatement()) {
            st.execute("""
                    CREATE TABLE orgs (
                        id UUID PRIMARY KEY,
                        name VARCHAR(255) NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE customers (
                        id UUID PRIMARY KEY,
                        org_id UUID NOT NULL,
                        email VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP NULL,
                        CONSTRAINT fk_customers_org FOREIGN KEY (org_id) REFERENCES orgs(id)
                    )""");
            st.execute("CREATE VIEW active_customers AS SELECT id, email FROM customers");
        }
    }

    @AfterEach
    void dropSourceDatabase() throws SQLException {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    @Test
    void dbSchemaConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(DbSchemaConnector.CONNECTOR_TYPE);
    }

    @Test
    void emitsOneDictionaryChunkPerTableAndView() throws ConnectorException {
        var source = newDbSource("db-all-kb", null);

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("PUBLIC.ORGS", "PUBLIC.CUSTOMERS", "PUBLIC.ACTIVE_CUSTOMERS");
    }

    @Test
    void customerDictionaryCapturesColumnsPrimaryKeyAndForeignKey() throws ConnectorException {
        var source = newDbSource("db-detail-kb", "{\"includeTables\":[\"CUSTOMERS\"]}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).hasSize(1);
        KnowledgeChunk customers = chunks.get(0);
        assertThat(customers.getLocator()).isEqualTo("PUBLIC.CUSTOMERS");
        assertThat(customers.getContent())
                .contains("Table: PUBLIC.CUSTOMERS")
                .contains("- EMAIL CHARACTER VARYING(255) NOT NULL")
                .contains("- CREATED_AT TIMESTAMP NULL")
                .contains("Primary key: ID")
                .contains("ORG_ID -> PUBLIC.ORGS(ID)");
        assertThat(customers.getMetadataJson())
                .contains("\"schema\":\"PUBLIC\"")
                .contains("\"table\":\"CUSTOMERS\"");
    }

    @Test
    void excludeViewsWhenIncludeViewsFalse() throws ConnectorException {
        var source = newDbSource("db-noviews-kb", "{\"includeViews\":false}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("PUBLIC.ORGS", "PUBLIC.CUSTOMERS");
    }

    @Test
    void excludeTablesGlobSkipsMatches() throws ConnectorException {
        var source = newDbSource("db-exclude-kb", "{\"excludeTables\":[\"active_*\",\"orgs\"]}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("PUBLIC.CUSTOMERS");
    }

    @Test
    void systemSchemasAreNotIndexedByDefault() throws ConnectorException {
        var source = newDbSource("db-sys-kb", null);

        connectorDispatcher.sync(source.getId());

        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).noneMatch(c -> c.getLocator().startsWith("INFORMATION_SCHEMA"));
    }

    @Test
    void unreachableDatabaseThrowsConnectorExceptionAndMarksFailed() {
        var source = newDbSourceWithUri("db-bad-kb",
                "jdbc:h2:mem:nonexistent_" + UUID.randomUUID() + ";IFEXISTS=TRUE", null);

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    // --- helpers -------------------------------------------------------------

    private KnowledgeSource newDbSource(String kbName, String configJson) {
        return newDbSourceWithUri(kbName, sourceDbUrl, configJson);
    }

    private KnowledgeSource newDbSourceWithUri(String kbName, String uri, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                DbSchemaConnector.CONNECTOR_TYPE,
                "schema",
                uri,
                configJson,
                null);
    }
}
