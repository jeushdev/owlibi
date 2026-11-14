package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.JsonBody;
import dev.owlibi.reference.templates.TemplateCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1",produces="application/json")
public class ProjectController {
    private final ProjectService projects;
    private final JsonBody bodies;
    public ProjectController(ProjectService projects,JsonBody bodies) { this.projects=projects; this.bodies=bodies; }
    @GetMapping("/me/projects") public ProjectService.Page list(@AuthenticationPrincipal AccountPrincipal actor,
        @RequestParam(name="limit",required=false) String limit,@RequestParam(name="cursor",required=false) String cursor,HttpServletRequest request) { bodies.empty(request); return projects.list(actor.id(),limit,cursor); }
    @PostMapping("/projects") public ResponseEntity<ProjectService.Created> create(@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request) {
        projects.creationAttempt(actor.id());
        var body=bodies.readTyped(request,Map.of("name",JsonBody.Kind.STRING,"description",JsonBody.Kind.STRING,"template",JsonBody.Kind.OBJECT,"settings",JsonBody.Kind.OBJECT));
        var selection=TemplateCatalog.selection(body.get("template"));
        ProjectRules.Values rules=null;
        if (body.has("settings")) {
            if (body.get("settings").isNull()) throw dev.owlibi.reference.web.ApiFailure.validation("settings");
            JsonBody.validate(body.get("settings"),ProjectRules.VALUES); rules=ProjectRules.normalize(body.get("settings"),null);
        }
        return ResponseEntity.status(201).body(projects.create(actor.id(),JsonBody.string(body,"name"),JsonBody.string(body,"description"),selection,rules));
    }
    @PostMapping("/projects/{id}/invitations") public ResponseEntity<ProjectService.Invitation> invite(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("id") UUID id,HttpServletRequest request) {
        projects.invitationAttempt(actor.id(),id); bodies.empty(request); return ResponseEntity.status(201).body(projects.invite(actor.id(),id));
    }
    @PostMapping("/invitations/accept") public Map<String,UUID> accept(@AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request) {
        projects.redemptionAttempt(request.getRemoteAddr());
        var body=bodies.read(request,"token");
        return Map.of("projectId",projects.accept(actor.id(),JsonBody.string(body,"token")));
    }
}
