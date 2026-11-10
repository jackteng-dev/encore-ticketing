package dev.jackteng.encore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TicketApplication {
 public static void main(String[] args) { SpringApplication.run(TicketApplication.class,args); }
}
