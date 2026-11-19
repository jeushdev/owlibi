package dev.owlibi.reference.projects;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/projects/{id}/settings",produces="application/json")
public class SettingsController {
    private final ProjectSettingsService settings;
    private final JsonBody bodies;
    public SettingsController(ProjectSettingsService settings,JsonBody bodies) { this.settings=settings; this.bodies=bodies; }
    @GetMapping public ProjectRules.Settings read(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("id") UUID id,HttpServletRequest request) {
        noQuery(request); bodies.empty(request); return settings.read(actor.id(),id);
    }
    @PatchMapping public ProjectRules.Settings edit(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("id") UUID id,HttpServletRequest request) {
        settings.requireLeader(actor.id(),id); noQuery(request); var body=bodies.readTyped(request,ProjectRules.PATCH);
        if (body.has("effortScaleExamples") && !body.get("effortScaleExamples").isNull())
            for(var item:body.get("effortScaleExamples")) JsonBody.validate(item,java.util.Map.of("points",JsonBody.Kind.STRING,"label",JsonBody.Kind.STRING));
        return settings.edit(actor.id(),id,body);
    }
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ApiFailure.malformed(); }
}
