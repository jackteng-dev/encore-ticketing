package dev.elpsy.ticket.runtime;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** DB-backed API readiness, not MQ/Redis health or permission to accept a purchase. */
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ReadinessController {
 private final JdbcTemplate db;
 public ReadinessController(JdbcTemplate db) { this.db=db; }
 @GetMapping("/api/public/readiness") public ResponseEntity<Map<String,String>> readiness() {
  try {
   db.queryForObject("SELECT 1",Integer.class);
   return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("status","READY"));
  } catch(org.springframework.dao.DataAccessException e) {
   return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("status","UNAVAILABLE"));
  }
 }
}
