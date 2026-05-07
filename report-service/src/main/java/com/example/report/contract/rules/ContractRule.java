package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * Phase 2 Program 1 — Contract validation rule SPI.
 *
 * <p>Spec §2.3 RC-000..RC-010 rules. Each rule is stateless; called once
 * per {@link ReportDefinition}. Returns 0..N violations.
 *
 * <p><strong>Build-time only</strong>: implementations no {@code @Component} /
 * {@code @Configuration} (production component scan'de aktive olmaz; CI'da
 * {@code mvn test} {@code @ParameterizedTest} registry sweep'inde çağrılır).
 */
public interface ContractRule {

    /** Rule ID (RC-000..RC-010). */
    String ruleId();

    /**
     * Validate a report definition against this rule.
     *
     * @param def report registry entry
     * @return zero or more violations; empty list = rule passes
     */
    List<ContractViolation> validate(ReportDefinition def);
}
