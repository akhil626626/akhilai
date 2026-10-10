package ai.akhil.market;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import static ai.akhil.market.MarketModels.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MarketException.class)
    public ResponseEntity<ApiError> marketError(MarketException exception) {
        return ResponseEntity.status(exception.status).body(new ApiError(exception.code, exception.getMessage()));
    }
}
