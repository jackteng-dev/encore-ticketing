package dev.jackteng.encore.tickets;

import dev.jackteng.encore.tickets.AdmissionWindowModels.*;
import dev.jackteng.encore.tickets.TicketModels.Settings;
import dev.jackteng.encore.customer.CustomerService.Principal;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.common.ApiErrors.Problem;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdmissionWindowController {
 private final AdmissionWindowService service;
 public AdmissionWindowController(AdmissionWindowService service) {this.service=service;}
 private String actor(java.security.Principal actor,String expected) {
  if(expected!=null&&!expected.equals(actor.getName()))throw new Problem(401,"STAFF_CHANGED","請由原員工核對時間窗操作");
  return actor.getName();
 }
 @GetMapping("/api/admin/admissions/windows/settings") public Settings settings(java.security.Principal actor) {return service.settings(actor.getName());}
 @GetMapping("/api/admin/admissions/windows/{id}") public Status status(java.security.Principal actor,@PathVariable UUID id) {return service.status(actor.getName(),id);}
 @GetMapping("/api/admin/admissions/windows/{id}/audit") public Page<Recovery> audit(java.security.Principal actor,@PathVariable UUID id,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int size) {return service.audit(actor.getName(),id,page,size);}
 @GetMapping("/api/customer/orders/{id}/admission-window") public Status customer(@AuthenticationPrincipal Principal actor,@PathVariable UUID id) {return service.customerStatus(actor,id);}
 @PostMapping("/api/admin/admissions/windows/{id}") public Recovery configure(java.security.Principal actor,@PathVariable UUID id,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Configure body) {return service.configure(actor(actor,expected),id,body);}
 @PostMapping("/api/admin/admissions/windows/{id}/receipt") public Recovery receipt(java.security.Principal actor,@PathVariable UUID id,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Configure body) {return service.receipt(actor(actor,expected),id,body);}
 @PostMapping("/api/admin/admissions/windows/{id}/stop") public Recovery stop(java.security.Principal actor,@PathVariable UUID id,@RequestHeader(value="X-Expected-Staff-Username",required=false)String expected,@Valid @RequestBody Configure body) {return service.stop(actor(actor,expected),id,body);}
}
