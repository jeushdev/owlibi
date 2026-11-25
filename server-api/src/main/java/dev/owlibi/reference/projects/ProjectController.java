package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.templates.TemplateCatalog;
import dev.owlibi.reference.web.ApiFailure;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for listing and creating projects and for inviting and joining members. */
@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
public class ProjectController {
    private static final Map<String, JsonBody.Kind> CREATE_FIELDS = Map.of(
        "name", JsonBody.Kind.STRING,
        "description", JsonBody.Kind.STRING,
        "template", JsonBody.Kind.OBJECT,
        "settings", JsonBody.Kind.OBJECT);

    private final ProjectService projects;
    private final JsonBody bodies;

    public ProjectController(ProjectService projects, JsonBody bodies) {
        this.projects = projects;
        this.bodies = bodies;
    }

    /** The caller's projects, newest first, one page at a time. */
    @GetMapping("/me/projects")
    public ProjectService.Page list(
            @AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletRequest request) {
        bodies.empty(request);
        return projects.list(actor.id(), limit, cursor);
    }

    /** Creates a project led by the caller, optionally from a template and with initial rules. */
    @PostMapping("/projects")
    public ResponseEntity<ProjectService.Created> create(@AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request) {
        projects.creationAttempt(actor.id());
        var body = bodies.readTyped(request, CREATE_FIELDS);
        var selection = TemplateCatalog.selection(body.get("template"));
        ProjectRules.Values rules = null;
        if (body.has("settings")) {
            if (body.get("settings").isNull()) {
                throw ApiFailure.validation("settings");
            }
            JsonBody.validate(body.get("settings"), ProjectRules.VALUES);
            rules = ProjectRules.normalize(body.get("settings"), null);
        }
        var created = projects.create(actor.id(), JsonBody.string(body, "name"), JsonBody.string(body, "description"), selection, rules);
        return ResponseEntity.status(201).body(created);
    }

    /** Creates a one-use invitation link for the project. Leader only. */
    @PostMapping("/projects/{id}/invitations")
    public ResponseEntity<ProjectService.Invitation> invite(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("id") UUID id,
            HttpServletRequest request) {
        projects.invitationAttempt(actor.id(), id);
        bodies.empty(request);
        return ResponseEntity.status(201).body(projects.invite(actor.id(), id));
    }

    /** Joins the caller to the project an invitation token belongs to. */
    @PostMapping("/invitations/accept")
    public Map<String, UUID> accept(@AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request) {
        projects.redemptionAttempt(request.getRemoteAddr());
        var body = bodies.read(request, "token");
        return Map.of("projectId", projects.accept(actor.id(), JsonBody.string(body, "token")));
    }
}
