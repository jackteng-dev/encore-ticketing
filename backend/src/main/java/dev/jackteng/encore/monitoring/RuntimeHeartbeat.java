package dev.jackteng.encore.monitoring;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
@Component
@EnableConfigurationProperties(MonitoringSettings.class)
public class RuntimeHeartbeat {
 private final UUID boot=UUID.randomUUID();private final HeartbeatStore store;private final MonitoringSettings settings;
 private final String role;private final ObjectProvider<SimpleMessageListenerContainer> listeners;
 public RuntimeHeartbeat(HeartbeatStore store,MonitoringSettings settings,Environment env,ObjectProvider<SimpleMessageListenerContainer> listeners){this.store=store;this.settings=settings;this.listeners=listeners;role=env.acceptsProfiles(Profiles.of("api"))?"API":env.acceptsProfiles(Profiles.of("worker"))?"WORKER":"EMBEDDED";}
 public String role(){return role;}
 @EventListener(ApplicationReadyEvent.class) public void ready(){pulse();}
 @Scheduled(fixedDelayString="${ticket.monitoring.heartbeat-ms:3000}",initialDelayString="${ticket.monitoring.initial-delay-ms:5000}")
 public void pulse(){if(!settings.enabled())return;try{var listener=listeners.getIfAvailable();store.pulse(boot,role,!role.equals("API")&&listener!=null&&listener.isRunning());}catch(org.springframework.dao.DataAccessException e){org.slf4j.LoggerFactory.getLogger(RuntimeHeartbeat.class).warn("Runtime heartbeat unavailable; observation may become stale ({})",e.getClass().getSimpleName());}}
}
