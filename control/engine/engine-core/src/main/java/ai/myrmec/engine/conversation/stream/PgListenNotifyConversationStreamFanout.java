package ai.myrmec.engine.conversation.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Cross-instance fan-out backed by Postgres {@code LISTEN/NOTIFY}.
 *
 * <p>Wire format (single Postgres channel for the whole cluster):
 * <pre>
 *   { "originInstanceId": "uuid", "conversationId": "uuid", "frame": "&lt;stream-json&gt;" }
 * </pre>
 * The listener loop deserialises every notification, skips frames whose
 * {@code originInstanceId} matches our own (to avoid self-delivery
 * since {@code ConversationStreamBroker.broadcast} already did the
 * local fan-out), and delegates the rest to the broker via the
 * remote-handler callback.</p>
 *
 * <h2>Operational notes</h2>
 * <ul>
 *   <li>Postgres caps NOTIFY payloads at 8000 bytes. Realistic streaming
 *       deltas are well under that; over-size frames are logged + dropped
 *       (cross-instance only — local delivery already happened).</li>
 *   <li>The listener runs on a single dedicated thread that calls
 *       {@code PGConnection.getNotifications(timeoutMs)} in a loop. A
 *       blocking driver call is fine — it's the only thing the thread
 *       does — and the JDBC connection is held open for the lifetime
 *       of the application context.</li>
 *   <li>{@code pg_notify} for publishing keeps the call path simple:
 *       one autocommitted SQL round-trip per frame. For very high
 *       volumes a future variant could batch via an outbox table; not
 *       worth the complexity for the V1 single-channel use case.</li>
 * </ul>
 *
 * <p>Lifecycle: {@link #start()} establishes the listen connection and
 * spins up the loop; {@link #stop()} signals shutdown and closes the
 * connection. Both are idempotent and safe to call from Spring's
 * {@code @PostConstruct} / {@code @PreDestroy} hooks.</p>
 */
@Slf4j
public class PgListenNotifyConversationStreamFanout implements ConversationStreamFanout {

    public static final String DEFAULT_CHANNEL = "myrmec_conv_stream";

    private static final int LISTEN_POLL_MS = 1_000;
    private static final int MAX_NOTIFY_PAYLOAD_BYTES = 7_500;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String instanceId = UUID.randomUUID().toString();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Connection listenConnection;
    private volatile ExecutorService loopExecutor;
    private volatile BiConsumer<UUID, String> remoteHandler = (id, frame) -> { };

    public PgListenNotifyConversationStreamFanout(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, DEFAULT_CHANNEL);
    }

    public PgListenNotifyConversationStreamFanout(DataSource dataSource,
                                                  ObjectMapper objectMapper,
                                                  String channel) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    @Override
    public void setRemoteHandler(BiConsumer<UUID, String> handler) {
        this.remoteHandler = handler == null ? (id, frame) -> { } : handler;
    }

    @Override
    public void publish(UUID conversationId, String jsonFrame) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("originInstanceId", instanceId);
        envelope.put("conversationId", conversationId.toString());
        envelope.put("frame", jsonFrame);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("Failed to encode fanout envelope for conversation {}: {}",
                    conversationId, e.toString());
            return;
        }
        if (payload.getBytes().length > MAX_NOTIFY_PAYLOAD_BYTES) {
            log.warn("Skipping cross-instance fanout for conversation {} — envelope is {} bytes (> {} cap). "
                            + "Local delivery already happened; remote viewers will not see this frame.",
                    conversationId, payload.getBytes().length, MAX_NOTIFY_PAYLOAD_BYTES);
            return;
        }
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
            ps.setString(1, channel);
            ps.setString(2, payload);
            ps.execute();
        } catch (Exception e) {
            // Broker swallows runtime exceptions, but log loudly anyway.
            throw new IllegalStateException("pg_notify on channel " + channel + " failed", e);
        }
    }

    @PostConstruct
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            listenConnection = dataSource.getConnection();
            try (Statement st = listenConnection.createStatement()) {
                // Identifier must be quoted-safe; the channel name is admin-
                // controlled (config property) so simple inline interpolation
                // is acceptable — but we still reject non-identifier chars.
                if (!channel.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Illegal NOTIFY channel name: " + channel);
                }
                st.execute("LISTEN " + channel);
            }
            loopExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pg-conv-stream-listener");
                t.setDaemon(true);
                return t;
            });
            loopExecutor.submit(this::pollLoop);
            log.info("Postgres LISTEN/NOTIFY conversation fanout started on channel '{}' (instance {})",
                    channel, instanceId);
        } catch (Exception e) {
            running.set(false);
            closeQuietly();
            throw new IllegalStateException("Failed to start Postgres conversation fanout", e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly();
        if (loopExecutor != null) {
            loopExecutor.shutdownNow();
            loopExecutor = null;
        }
        log.info("Postgres LISTEN/NOTIFY conversation fanout stopped (instance {})", instanceId);
    }

    private void closeQuietly() {
        Connection conn = this.listenConnection;
        this.listenConnection = null;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // shutdown path; best-effort
            }
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                PGConnection pg = listenConnection.unwrap(PGConnection.class);
                PGNotification[] notifications = pg.getNotifications(LISTEN_POLL_MS);
                if (notifications == null) {
                    continue;
                }
                for (PGNotification n : notifications) {
                    handleNotification(n.getParameter());
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("Postgres conversation fanout loop hit an error; will retry: {}", e.toString());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Visible for testing — decode + dispatch one notification payload. */
    void handleNotification(String payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        try {
            JsonNode env = objectMapper.readTree(payload);
            String origin = textOrNull(env, "originInstanceId");
            if (instanceId.equals(origin)) {
                // Local origin — already delivered by ConversationStreamBroker.broadcast.
                return;
            }
            String convIdStr = textOrNull(env, "conversationId");
            String frame = textOrNull(env, "frame");
            if (convIdStr == null || frame == null) {
                log.warn("Discarding malformed fanout envelope: {}", payload);
                return;
            }
            UUID conversationId;
            try {
                conversationId = UUID.fromString(convIdStr);
            } catch (IllegalArgumentException iae) {
                log.warn("Discarding envelope with invalid conversationId: {}", convIdStr);
                return;
            }
            remoteHandler.accept(conversationId, frame);
        } catch (Exception e) {
            log.warn("Failed to dispatch fanout envelope ({}): {}", e.toString(), payload);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Visible for testing. */
    String instanceId() {
        return instanceId;
    }
}
