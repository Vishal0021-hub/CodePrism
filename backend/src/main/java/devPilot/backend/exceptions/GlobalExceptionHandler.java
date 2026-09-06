package devPilot.backend.exceptions;

//import devPilot.backend.security.UnathorizedException;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundExceptions.class)
    ResponseEntity<Map<String,Object>> handleNotFound(ChangeSetPersister.NotFoundException ex){
        return error(HttpStatus.NOT_FOUND,ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Map<String,Object>> handleBadRequest(BadRequestException ex){
        return error(HttpStatus.BAD_REQUEST,ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String,Object>> handleUnathorized(UnauthorizedException ex){
        return error(HttpStatus.UNAUTHORIZED,ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException ex){
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ":" + err.getDefaultMessage())
                .orElse("Validation failed");
        return error(HttpStatus.BAD_REQUEST,message);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> handleGeneric(Exception ex){
        return error(HttpStatus.INTERNAL_SERVER_ERROR,ex.getMessage() !=null ? ex.getMessage() : "Unexpected error");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status ,String message) {
        return ResponseEntity.status(status).body(Map.of(
                "Status",status.value(),
                "error",status.getReasonPhrase(),
                "message",message,
                "timestamp", Instant.now().toString()
        ));
    }
}
