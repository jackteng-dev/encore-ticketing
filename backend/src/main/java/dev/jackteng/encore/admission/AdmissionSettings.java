package dev.jackteng.encore.admission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public record AdmissionSettings(boolean enabled,String namespace,int active,int queue,int permitSeconds,int idleSeconds,int waitSeconds,int joinRate,int placeRate) {
 public AdmissionSettings(@Value("${ticket.admission.enabled}") boolean enabled,@Value("${ticket.admission.namespace}") String namespace,
  @Value("${ticket.admission.active}") int active,@Value("${ticket.admission.queue}") int queue,@Value("${ticket.admission.permit-seconds}") int permitSeconds,
  @Value("${ticket.admission.idle-seconds}") int idleSeconds,@Value("${ticket.admission.wait-seconds}") int waitSeconds,
  @Value("${ticket.admission.join-rate}") int joinRate,@Value("${ticket.admission.place-rate}") int placeRate){
  if(!namespace.matches("encore-demo-[a-z0-9-]{1,48}")||active<1||active>20||queue<1||queue>200||permitSeconds<2||permitSeconds>120||idleSeconds<5||idleSeconds>60||waitSeconds<idleSeconds||waitSeconds>600||joinRate<1||joinRate>600||placeRate<1||placeRate>100)throw new IllegalArgumentException("Invalid isolated admission configuration");
  this.enabled=enabled;this.namespace=namespace;this.active=active;this.queue=queue;this.permitSeconds=permitSeconds;this.idleSeconds=idleSeconds;this.waitSeconds=waitSeconds;this.joinRate=joinRate;this.placeRate=placeRate;
 }
}
