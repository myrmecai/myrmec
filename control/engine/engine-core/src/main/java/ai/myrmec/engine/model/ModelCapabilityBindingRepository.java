// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModelCapabilityBindingRepository
        extends JpaRepository<ModelCapabilityBinding, ModelCapabilityBindingId> {
}
