package ai.myrmec.engine.conversation.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

/**
 * Wiring for {@link ConversationStreamFanout}.
 *
 * <p>Default (single-instance) deployments get
 * {@link NoopConversationStreamFanout}. Clustered deployments opt into
 * Postgres {@code LISTEN/NOTIFY} fan-out by setting
 * {@code myrmec.conversation.fanout.postgres.enabled=true} — the
 * resulting {@link PgListenNotifyConversationStreamFanout} bean wins
 * because the noop bean only registers when no other
 * {@link ConversationStreamFanout} is already on the context.</p>
 */
@Configuration
public class ConversationStreamFanoutAutoConfiguration {

    /**
     * Postgres-backed clustered fanout. The {@link Order} guarantees this
     * bean definition is processed before the noop fallback below, so
     * the {@link ConditionalOnMissingBean} on the fallback yields.
     */
    @Bean(destroyMethod = "stop")
    @Order(0)
    @ConditionalOnProperty(
            name = "myrmec.conversation.fanout.postgres.enabled",
            havingValue = "true")
    public ConversationStreamFanout pgListenNotifyConversationStreamFanout(
            DataSource dataSource,
            ObjectMapper objectMapper,
            @Value("${myrmec.conversation.fanout.postgres.channel:"
                    + PgListenNotifyConversationStreamFanout.DEFAULT_CHANNEL + "}")
            String channel) {
        return new PgListenNotifyConversationStreamFanout(dataSource, objectMapper, channel);
    }

    @Bean
    @ConditionalOnMissingBean(ConversationStreamFanout.class)
    public ConversationStreamFanout noopConversationStreamFanout() {
        return new NoopConversationStreamFanout();
    }
}
