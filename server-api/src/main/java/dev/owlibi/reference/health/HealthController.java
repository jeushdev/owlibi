package dev.owlibi.reference.health;
import java.util.Map;
import dev.owlibi.reference.web.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class HealthController {
    private final DatabaseReadinessService database;
    public HealthController(DatabaseReadinessService database) { this.database = database; }
    @GetMapping(value="/api/v1/health",produces="application/json") public ResponseEntity<?> health() {
        return database.isReady() ? ResponseEntity.ok(Map.of("status", "UP"))
            : ResponseEntity.status(503).body(new ApiError("SERVICE_UNAVAILABLE", "Service temporarily unavailable", Map.of()));
    }
}
