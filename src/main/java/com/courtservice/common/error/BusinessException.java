package com.courtservice.common.error;

/**
 * Base class for all expected, client-correctable or client-visible error conditions.
 *
 * <p>Every subclass maps to a single {@link ErrorCode}. Controllers never build error
 * responses manually; they let {@code GlobalExceptionHandler} translate this exception
 * into a Problem Details response.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultDetail());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}