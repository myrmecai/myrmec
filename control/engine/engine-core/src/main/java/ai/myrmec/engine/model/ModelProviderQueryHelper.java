// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.model;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.myrmec.engine.connection.ConnectionConfig;
import ai.myrmec.engine.connection.QConnectionConfig;
/**
 * QueryDSL-backed query for ModelProviderConfig that joins to
 * ConnectionConfig to resolve the config name without a JPA association.
 *
 * <p>Keeps the join logic separate from the Spring Data JPA repository
 * (which only handles simple derived queries). The {@code Q*} classes
 * are APT-generated from {@link ModelProviderConfig} and
 * {@link ai.myrmec.engine.connection.ConnectionConfig}.</p>
 */
@Repository
public class ModelProviderQueryHelper {

    private final JPAQueryFactory queryFactory;

    public ModelProviderQueryHelper(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * Fetch all providers with their linked ConnectionConfig name (LEFT JOIN).
     * Providers without a linked config get a null name.
     *
     * @return list of [provider entity, connectionConfigName] tuples
     */
    public List<Tuple> findAllWithConnectionConfigName() {
        QModelProviderConfig p = QModelProviderConfig.modelProviderConfig;
        QConnectionConfig c = QConnectionConfig.connectionConfig;

        return queryFactory
                .select(p, c.name)
                .from(p)
                .leftJoin(c).on(p.connectionConfigId.eq(c.id))
                .fetch();
    }

    /**
     * Fetch all ACTIVE providers with their linked ConnectionConfig name (LEFT JOIN).
     *
     * @return list of [provider entity, connectionConfigName] tuples
     */
    public List<Tuple> findAllActiveWithConnectionConfigName() {
        QModelProviderConfig p = QModelProviderConfig.modelProviderConfig;
        QConnectionConfig c = QConnectionConfig.connectionConfig;

        return queryFactory
                .select(p, c.name)
                .from(p)
                .leftJoin(c).on(p.connectionConfigId.eq(c.id))
                .where(p.status.eq(ModelStatus.ACTIVE))
                .fetch();
    }

    /**
     * Fetch a single provider by code with its linked ConnectionConfig name (LEFT JOIN).
     *
     * @param code the provider code (primary key)
     * @return a [provider entity, connectionConfigName] tuple, or null if not found
     */
    public Tuple findWithConnectionConfigNameByCode(String code) {
        QModelProviderConfig p = QModelProviderConfig.modelProviderConfig;
        QConnectionConfig c = QConnectionConfig.connectionConfig;

        return queryFactory
                .select(p, c.name)
                .from(p)
                .leftJoin(c).on(p.connectionConfigId.eq(c.id))
                .where(p.code.eq(code))
                .fetchOne();
    }
}