package dev.owlibi.reference.templates;

import dev.owlibi.reference.web.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/templates",produces="application/json")
public class TemplateController {
    private final TemplateCatalog catalogue;
    private final JsonBody bodies;
    public TemplateController(TemplateCatalog catalogue,JsonBody bodies) { this.catalogue=catalogue; this.bodies=bodies; }
    @GetMapping public Map<String,List<TemplateCatalog.Template>> list(HttpServletRequest request) {
        bodies.empty(request); if (!request.getParameterMap().isEmpty()) throw ApiFailure.malformed(); return Map.of("items",catalogue.list());
    }
    @GetMapping("/{id}") public TemplateCatalog.Template read(@PathVariable("id") String id,HttpServletRequest request) {
        bodies.empty(request); var query=request.getParameterMap();
        if (!Set.of("version").containsAll(query.keySet()) || query.values().stream().anyMatch(v -> v.length!=1)) throw ApiFailure.malformed();
        String value=request.getParameter("version"); Integer version=null;
        if (value!=null) {
            if (!value.matches("[1-9][0-9]{0,9}")) throw ApiFailure.malformed();
            try { version=Integer.parseInt(value); } catch(NumberFormatException invalid) { throw ApiFailure.malformed(); }
        }
        return catalogue.read(id,version);
    }
}
