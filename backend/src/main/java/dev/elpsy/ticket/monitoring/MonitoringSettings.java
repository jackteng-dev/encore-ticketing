package dev.elpsy.ticket.monitoring;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("ticket.monitoring")
public record MonitoringSettings(boolean enabled,int staleSeconds,int heartbeatMs,int expectedApis,int expectedWorkers) {
 public MonitoringSettings {if(staleSeconds<5||staleSeconds>120||heartbeatMs<500||heartbeatMs>10000||staleSeconds*1000<3*heartbeatMs||expectedApis<1||expectedApis>16||expectedWorkers<1||expectedWorkers>16)throw new IllegalArgumentException("Bounded monitoring interval/role expectations required");}
}
