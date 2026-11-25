package dev.owlibi.templates;

import dev.owlibi.web.ApiFailure;
import dev.owlibi.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only HTTP endpoints for the project template catalogue. */
@RestController
@RequestMapping(value = "/api/v1/templates", produces = "application/json")
public class TemplateController {
    private final TemplateCatalog catalogue;
    private final JsonBody bodies;

    /** Wires the catalogue and the empty-body check. */
    public TemplateController(TemplateCatalog catalogue, JsonBody bodies) {
        this.catalogue = catalogue;
        this.bodies = bodies;
    }

    /** Lists the newest version of every template. Rejects any body or query parameter. */
    @GetMapping
    public Map<String, List<TemplateCatalog.Template>> list(HttpServletRequest request) {
        bodies.empty(request);
        if (!request.getParameterMap().isEmpty()) throw ApiFailure.malformed();
        return Map.of("items", catalogue.list());
    }

    /** Reads one template; an optional single {@code version} parameter picks an exact version. */
    @GetMapping("/{id}")
    public TemplateCatalog.Template read(@PathVariable("id") String id, HttpServletRequest request) {
        bodies.empty(request);
        var query = request.getParameterMap();
        if (!Set.of("version").containsAll(query.keySet()) || query.values().stream().anyMatch(values -> values.length != 1)) {
            throw ApiFailure.malformed();
        }
        String value = request.getParameter("version");
        Integer version = null;
        if (value != null) {
            if (!value.matches("[1-9][0-9]{0,9}")) throw ApiFailure.malformed();
            try {
                version = Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw ApiFailure.malformed();
            }
        }
        return catalogue.read(id, version);
    }
}
