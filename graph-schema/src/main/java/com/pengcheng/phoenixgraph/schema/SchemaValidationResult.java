package com.pengcheng.phoenixgraph.schema;

import java.util.List;

/**
 * Result of validating a {@link GraphSchema}: violations plus warnings.
 *
 * @param errors   validation violations (empty when the schema is valid)
 * @param warnings non-fatal issues (e.g. defaults that were applied implicitly)
 */
public record SchemaValidationResult(List<String> errors, List<String> warnings) {

    public boolean isValid() {
        return errors.isEmpty();
    }
}
