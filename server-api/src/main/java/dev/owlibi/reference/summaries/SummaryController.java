package dev.owlibi.reference.summaries;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.ApiFailure;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for a project's contribution and workload summaries. */
@RestController
@RequestMapping(value = "/api/v1/projects/{project}", produces = "application/json")
public class SummaryController {
    private final SummaryService summaries;
    private final JsonBody bodies;

    public SummaryController(SummaryService summaries, JsonBody bodies) {
        this.summaries = summaries;
        this.bodies = bodies;
    }

    /** Each member's share of completed effort points. */
    @GetMapping("/contribution-summary")
    public SummaryDtos.Contribution contribution(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            HttpServletRequest request) {
        requireEmpty(request);
        return summaries.contribution(actor.id(), project);
    }

    /** Each member's remaining effort and due-date pressure. */
    @GetMapping("/workload-summary")
    public SummaryDtos.Workload workload(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            HttpServletRequest request) {
        requireEmpty(request);
        return summaries.workload(actor.id(), project);
    }

    /** Summaries take no query parameters and no body. */
    private void requireEmpty(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw ApiFailure.malformed();
        }
        bodies.empty(request);
    }
}
