package dev.owlibi.reference.summaries;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/projects/{project}",produces="application/json")
public class SummaryController {
    private final SummaryService summaries;
    private final JsonBody bodies;
    public SummaryController(SummaryService summaries,JsonBody bodies) { this.summaries=summaries;this.bodies=bodies; }
    private void empty(HttpServletRequest request) { if(!request.getParameterMap().isEmpty()) throw ApiFailure.malformed();bodies.empty(request); }
    @GetMapping("/contribution-summary") public SummaryDtos.Contribution contribution(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,HttpServletRequest request) {
        empty(request);return summaries.contribution(actor.id(),project);
    }
    @GetMapping("/workload-summary") public SummaryDtos.Workload workload(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,HttpServletRequest request) {
        empty(request);return summaries.workload(actor.id(),project);
    }
}
