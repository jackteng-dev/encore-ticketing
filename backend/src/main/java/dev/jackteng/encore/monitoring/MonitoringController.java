package dev.jackteng.encore.monitoring;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class MonitoringController {
 private final MonitoringService service;
 public MonitoringController(MonitoringService service){this.service=service;}
 @GetMapping("/api/admin/monitoring") public ResponseEntity<MonitoringService.Snapshot> read(Principal principal){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.snapshot(principal.getName()));}
}
