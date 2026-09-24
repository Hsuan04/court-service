package com.courtservice.common.error;

/**
 * Thrown when a time range is invalid, such as an end time that is not after its start time.
 */
public class InvalidTimeRangeException extends BusinessException {

    public InvalidTimeRangeException(String detail) {
        super(ErrorCode.INVALID_TIME_RANGE, detail);
    }
}
