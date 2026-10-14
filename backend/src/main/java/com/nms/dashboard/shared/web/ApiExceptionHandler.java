package com.nms.dashboard.shared.web;

import com.nms.dashboard.analytics.domain.QueryException;
import com.nms.dashboard.shared.error.ConflictException;
import com.nms.dashboard.shared.error.ForbiddenException;
import com.nms.dashboard.shared.error.NotFoundException;
import com.nms.dashboard.shared.error.ValidationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error as {@code {"error": "..."}}: the domain's errors carry no HTTP, this is where they get their status —
 * invalid input 400, not allowed 403, not found 404, conflict 409. A QueryException's message names the fields that do exist.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(QueryException.class)
  ResponseEntity<Map<String, String>> badQuery(QueryException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(ForbiddenException.class)
  ResponseEntity<Map<String, String>> forbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of("error", "Unreadable request body: " + e.getMostSpecificCause().getMessage()));
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<Map<String, String>> invalid(ValidationException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(ConflictException.class)
  ResponseEntity<Map<String, String>> conflict(ConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Map<String, String>> missing(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found: /" + e.getResourcePath()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception e) {
    log.error("Request failed", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
