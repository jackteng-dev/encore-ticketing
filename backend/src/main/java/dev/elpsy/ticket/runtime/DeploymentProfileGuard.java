package dev.elpsy.ticket.runtime;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Reject mixed deployment roles; the no-profile embedded demo remains compatible. */
@Component
public class DeploymentProfileGuard implements InitializingBean {
 private final Environment environment;
 public DeploymentProfileGuard(Environment environment) { this.environment=environment; }
 @Override public void afterPropertiesSet() {
  boolean api=environment.acceptsProfiles(Profiles.of("api"));
  boolean worker=environment.acceptsProfiles(Profiles.of("worker"));
  if(api&&worker)throw new IllegalStateException("Select exactly one deployment profile: api or worker");
  if(worker&&(!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type"))
    ||environment.getProperty("ticket.demo-bootstrap",Boolean.class,false)))
   throw new IllegalStateException("Worker requires no HTTP server and no demo bootstrap");
  if(api&&!"servlet".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type")))
   throw new IllegalStateException("API requires servlet mode");
 }
}
