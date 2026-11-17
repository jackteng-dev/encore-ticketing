package dev.jackteng.encore.catalog;
import dev.jackteng.encore.catalog.Models.Page;
import dev.jackteng.encore.catalog.TaxonomyModels.*;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class TaxonomyController {
 private final TaxonomyService service;
 public TaxonomyController(TaxonomyService service){this.service=service;}
 @GetMapping("/public/categories") public List<Category> categories(){return service.categories();}
 @GetMapping("/public/venues") public Page<Venue> venues(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="24") int size){return service.venues(page,size);}
 @GetMapping("/public/venues/{slug}") public Venue venue(@PathVariable String slug){return service.venue(slug);}
 @GetMapping("/public/performers") public Page<Performer> performers(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="24") int size){return service.performers(page,size);}
 @GetMapping("/public/performers/{slug}") public Performer performer(@PathVariable String slug){return service.performer(slug);}
 @GetMapping("/public/events/{id}/profile") public Profile publicProfile(@PathVariable UUID id){return service.profile(id,true);}

 @GetMapping("/admin/categories") public List<Category> adminCategories(){return service.categories();}
 @GetMapping("/admin/venues") public Page<Venue> adminVenues(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){return service.venues(page,size);}
 @PostMapping("/admin/venues") public Venue createVenue(Principal p,@Valid @RequestBody VenueInput in){return service.createVenue(p.getName(),in);}
 @PutMapping("/admin/venues/{id}") public Venue updateVenue(Principal p,@PathVariable UUID id,@Valid @RequestBody VenueInput in){return service.updateVenue(p.getName(),id,in);}
 @GetMapping("/admin/performers") public Page<Performer> adminPerformers(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size){return service.performers(page,size);}
 @PostMapping("/admin/performers") public Performer createPerformer(Principal p,@Valid @RequestBody PerformerInput in){return service.createPerformer(p.getName(),in);}
 @PutMapping("/admin/performers/{id}") public Performer updatePerformer(Principal p,@PathVariable UUID id,@Valid @RequestBody PerformerInput in){return service.updatePerformer(p.getName(),id,in);}
 @GetMapping("/admin/events/{id}/profile") public Profile adminProfile(@PathVariable UUID id){return service.profile(id,false);}
 @PutMapping("/admin/events/{id}/profile") public Profile updateProfile(Principal p,@PathVariable UUID id,@Valid @RequestBody ProfileInput in){return service.updateProfile(p.getName(),id,in);}
}
