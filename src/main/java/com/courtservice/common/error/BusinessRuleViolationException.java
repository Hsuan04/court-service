package com.courtservice.common.error;

/**
 * Thrown when a request is well-formed but violates a domain business rule,
 * such as booking a session that is already at capacity.
 */
public class BusinessRuleViolationException extends BusinessException {

    public BusinessRuleViolationException(String detail) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, detail);
    }
}