package com.challenge.balance.web;

import com.challenge.balance.exception.AccountAlreadyExistsException;
import com.challenge.balance.exception.AccountNotFoundException;
import com.challenge.balance.exception.IdempotencyConflictException;
import com.challenge.balance.exception.InsufficientFundsException;
import com.challenge.balance.exception.InvalidAmountException;
import com.challenge.balance.exception.SameAccountTransferException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(AccountNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<Object> handleAlreadyExists(AccountAlreadyExistsException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<Object> handleInvalidAmount(InvalidAmountException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", ex.getMessage(), request);
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ResponseEntity<Object> handleSameAccount(SameAccountTransferException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT_TRANSFER", ex.getMessage(), request);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Object> handleInsufficientFunds(InsufficientFundsException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Object> handleIdempotencyConflict(IdempotencyConflictException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, List<String>> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            addError(errors, error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                addError(errors, error.getObjectName(), error.getDefaultMessage()));
        return validationProblem(errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) {
            logger.error("Response validation failed", ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                    "An unexpected error occurred.", request);
        }
        Map<String, List<String>> errors = new TreeMap<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error ->
                        addError(errors, error.getField(), error.getDefaultMessage()));
                parameterErrors.getGlobalErrors().forEach(error ->
                        addError(errors, error.getObjectName(), error.getDefaultMessage()));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error ->
                        addError(errors, name == null ? "request" : name, error.getDefaultMessage()));
            }
        });
        ex.getCrossParameterValidationResults().forEach(error ->
                addError(errors, "request", error.getDefaultMessage()));
        return validationProblem(errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body must contain valid JSON with the expected field types.");
        return createResponseEntity(body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        logger.error("Unexpected request failure", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred.", request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail detail
                ? detail : ProblemDetail.forStatus(status);
        problem.setStatus(status.value());
        if (status.is5xxServerError()) {
            // Do not expose database errors, stack traces, or response validation details.
            problem.setDetail("An unexpected error occurred.");
            problem.setProperty("error", "INTERNAL_SERVER_ERROR");
            problem.setProperty("fieldErrors", Map.of());
        }
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("error")) {
            problem.setProperty("error", "HTTP_" + status.value());
        }
        if (!problem.getProperties().containsKey("fieldErrors")) {
            problem.setProperty("fieldErrors", Map.of());
        }
        // Preserve the original API's machine-readable code and message alongside Problem Detail.
        problem.setProperty("message", problem.getDetail());
        return super.createResponseEntity(problem, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(Map<String, List<String>> errors,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail body = body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.");
        body.setProperty("fieldErrors", errors);
        return createResponseEntity(body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> problem(HttpStatus status, String error, String message, WebRequest request) {
        return createResponseEntity(body(status, error, message), new HttpHeaders(), status, request);
    }

    private ProblemDetail body(HttpStatus status, String error, String message) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, message);
        body.setProperty("error", error);
        return body;
    }

    private void addError(Map<String, List<String>> errors, String field, String message) {
        errors.computeIfAbsent(field, ignored -> new ArrayList<>())
                .add(message == null ? "Invalid value" : message);
    }
}
