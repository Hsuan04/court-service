package com.courtservice.common.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Stream;

/**
 * Translates every exception reaching the API layer into a Problem Details response with
 * the shared extension fields. Client-correctable errors (4xx) are logged at WARN without a
 * stack trace; unexpected errors (5xx) are logged at ERROR with the full stack trace and
 * never expose their message to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception, WebRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        ProblemDetail problemDetail = problemDetailFactory.create(errorCode, exception.getMessage(), request);
        return respond(problemDetail, errorCode, exception);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException exception, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.RESOURCE_IN_USE,
                ErrorCode.RESOURCE_IN_USE.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.RESOURCE_IN_USE, exception);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException exception, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.CONCURRENT_MODIFICATION, exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.INTERNAL_ERROR, exception);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toValidationError)
                .toList();
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultDetail(), request, errors);
        return respond(problemDetail, ErrorCode.VALIDATION_FAILED, exception);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ValidationError> errors = exception.getValueResults().stream()
                .flatMap(this::toValidationErrors)
                .toList();
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultDetail(), request, errors);
        return respond(problemDetail, ErrorCode.VALIDATION_FAILED, exception);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.MALFORMED_REQUEST,
                ErrorCode.MALFORMED_REQUEST.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.MALFORMED_REQUEST, exception);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.INVALID_PARAMETER,
                ErrorCode.INVALID_PARAMETER.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.INVALID_PARAMETER, exception);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.INVALID_PARAMETER,
                ErrorCode.INVALID_PARAMETER.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.INVALID_PARAMETER, exception);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.ENDPOINT_NOT_FOUND,
                ErrorCode.ENDPOINT_NOT_FOUND.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.ENDPOINT_NOT_FOUND, exception);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = problemDetailFactory.create(ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.getDefaultDetail(), request);
        return respond(problemDetail, ErrorCode.METHOD_NOT_ALLOWED, exception);
    }

    private ResponseEntity<Object> respond(ProblemDetail problemDetail, ErrorCode errorCode, Exception exception) {
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("Unhandled exception mapped to {}", errorCode, exception);
        } else {
            log.warn("Request rejected with {}: {}", errorCode, exception.getMessage());
        }
        return ResponseEntity.status(errorCode.getHttpStatus()).body(problemDetail);
    }

    private ValidationError toValidationError(FieldError fieldError) {
        return new ValidationError(fieldError.getField(), fieldError.getDefaultMessage(), fieldError.getRejectedValue());
    }

    private Stream<ValidationError> toValidationErrors(ParameterValidationResult result) {
        String field = result.getMethodParameter().getParameterName();
        Object rejectedValue = result.getArgument();
        return result.getResolvableErrors().stream()
                .map(error -> new ValidationError(field, error.getDefaultMessage(), rejectedValue));
    }
}