package dev.elpsy.ticket.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentProfileGuardTest {
 private MockEnvironment env(String... profiles) {var e=new MockEnvironment();e.setActiveProfiles(profiles);return e;}
 @Test void embeddedDemoRemainsCompatible() {assertDoesNotThrow(()->new DeploymentProfileGuard(env()).afterPropertiesSet());}
 @Test void rejectsMixedProfiles() {assertThrows(IllegalStateException.class,()->new DeploymentProfileGuard(env("api","worker").withProperty("spring.main.web-application-type","none")).afterPropertiesSet());}
 @Test void workerCannotOpenHttp() {assertThrows(IllegalStateException.class,()->new DeploymentProfileGuard(env("worker").withProperty("spring.main.web-application-type","servlet")).afterPropertiesSet());}
 @Test void workerCannotBootstrap() {assertThrows(IllegalStateException.class,()->new DeploymentProfileGuard(env("worker").withProperty("spring.main.web-application-type","none").withProperty("ticket.demo-bootstrap","true")).afterPropertiesSet());}
 @Test void explicitWorkerCanStart() {assertDoesNotThrow(()->new DeploymentProfileGuard(env("worker").withProperty("spring.main.web-application-type","none")).afterPropertiesSet());}
 @Test void apiCannotLoseHttp() {assertThrows(IllegalStateException.class,()->new DeploymentProfileGuard(env("api").withProperty("spring.main.web-application-type","none")).afterPropertiesSet());}
 @Test void readinessFailureContainsNoDbDetails() {
  var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT 1",Integer.class)).thenThrow(new DataAccessResourceFailureException("private connection details"));
  var r=new ReadinessController(db).readiness();assertEquals(503,r.getStatusCode().value());assertEquals(java.util.Map.of("status","UNAVAILABLE"),r.getBody());assertEquals("no-store",r.getHeaders().getFirst("Cache-Control"));
 }
}
