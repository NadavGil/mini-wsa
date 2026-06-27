package com.akamai.miniwsa.web;

import com.akamai.miniwsa.dto.error.ApiError;
import com.akamai.miniwsa.service.BatchTooLargeException;
import com.akamai.miniwsa.service.FutureTimestampException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request body contains invalid fields",
                violations
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<ApiError.FieldViolation> violations = ex.getConstraintViolations()
                .stream()
                .map(cv -> new ApiError.FieldViolation(
                        cv.getPropertyPath().toString(), cv.getMessage()))
                .collect(Collectors.toList());
        // Do NOT expose ex.getMessage() — it leaks internal class names and Hibernate paths
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Constraint Violation",
                "One or more field constraints were violated",
                violations
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(FutureTimestampException.class)
    public ResponseEntity<ApiError> handleFutureTimestamp(FutureTimestampException ex) {
        return buildError(HttpStatus.UNPROCESSABLE_ENTITY, "Future Timestamp", ex.getMessage());
    }

    @ExceptionHandler(BatchTooLargeException.class)
    public ResponseEntity<ApiError> handleBatchTooLarge(BatchTooLargeException ex) {
        return buildError(HttpStatus.PAYLOAD_TOO_LARGE, "Batch Too Large", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return buildError(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        return buildError(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        return buildError(HttpStatus.CONFLICT, "Duplicate Event", "Event ID already exists");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneral(Exception ex) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred");
    }

    private ResponseEntity<ApiError> buildError(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(
                new ApiError(Instant.now(), status.value(), error, message, List.of())
        );
    }
}
