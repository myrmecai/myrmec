// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Composite key for {@link ModelCapabilityBinding}: a model code plus a
 * capability code.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ModelCapabilityBindingId implements Serializable {

    @Column(name = "model_code", nullable = false, length = 50)
    private String modelCode;

    @Column(name = "capability_code", nullable = false, length = 30)
    private String capabilityCode;
}
