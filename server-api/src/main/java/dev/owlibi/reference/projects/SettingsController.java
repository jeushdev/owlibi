package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.ApiFailure;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for reading and editing a project's rules (effort scale, overload threshold, near-due days). */
@RestController
@RequestMapping(value = "/api/v1/projects/{id}/settings", produces = "application/json")
public class SettingsController {
    private static final Map<String, JsonBody.Kind> EXAMPLE_FIELDS = Map.of(
        "points", JsonBody.Kind.STRING,
        "label", JsonBody.Kind.STRING);

    private final ProjectSettingsService settings;
    private final JsonBody bodies;

    public SettingsController(ProjectSettingsService settings, JsonBody bodies) {
        this.settings = settings;
        this.bodies = bodies;
    }

    /** The project's current rules. Any member may read them. */
    @GetMapping
    public ProjectRules.Settings read(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable("id") UUID id, HttpServletRequest request) {
        noQuery(request);
        bodies.empty(request);
        return settings.read(actor.id(), id);
    }

    /** Changes some or all of the project's rules. Leader only. */
    @PatchMapping
    public ProjectRules.Settings edit(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable("id") UUID id, HttpServletRequest request) {
        settings.requireLeader(actor.id(), id);
        noQuery(request);
        var body = bodies.readTyped(request, ProjectRules.PATCH);
        if (body.has("effortScaleExamples") && !body.get("effortScaleExamples").isNull()) {
            for (var item : body.get("effortScaleExamples")) {
                JsonBody.validate(item, EXAMPLE_FIELDS);
            }
        }
        return settings.edit(actor.id(), id, body);
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiFailure.malformed();
        }
    }
}
