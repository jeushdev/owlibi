package dev.owlibi.templates;

import dev.owlibi.projects.ProjectRules;
import dev.owlibi.web.ApiFailure;
import dev.owlibi.web.Fields;
import dev.owlibi.web.JsonBody;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable versioned data, not executable template policy or Task subclasses. */
@Component
public class TemplateCatalog {
    /** One task a template adds to a new project. */
    public record Starter(String key, String title, String description, String category, String effortPoints, String dueDate) {}

    /** Who a starter task is assigned to and its starting acceptance state. */
    public record Initialization(String assigneePolicy, String acceptancePolicy) {}

    /** One version of a project template: its categories, starter tasks and suggested settings. */
    public record Template(
        String id,
        String name,
        int version,
        List<String> categories,
        List<Starter> starterTasks,
        ProjectRules.Values suggestedSettings,
        Initialization initialization
    ) {
        public Template {
            categories = List.copyOf(categories);
            starterTasks = List.copyOf(starterTasks);
        }
    }

    /** A reference to one exact template version. */
    public record Ref(String id, int version) {}

    /** The template a client picked when creating a project. */
    public record Selection(String id, Integer version) {}

    private record Key(String id, int version) {}

    private static final List<String> ORDER = List.of("blank", "coding", "written", "reporting-presentation", "filming-video");

    private final Map<Key, Template> versions;
    private final Map<String, Template> current;

    /** Loads the catalogue bundled at {@code /templates/catalogue.json}. */
    public TemplateCatalog() {
        this(load());
    }

    /**
     * Validates every template, rejects duplicate id and version pairs and keeps the newest version of each id.
     * Every id in the fixed order must be present.
     */
    public TemplateCatalog(List<Template> source) {
        var all = new HashMap<Key, Template>();
        var latest = new HashMap<String, Template>();
        for (Template value : source) {
            Template normalized = validate(value);
            if (all.put(new Key(normalized.id(), normalized.version()), normalized) != null) {
                throw new IllegalStateException("Duplicate template identity");
            }
            latest.merge(normalized.id(), normalized, (existing, candidate) -> existing.version() > candidate.version() ? existing : candidate);
        }
        if (!latest.keySet().equals(Set.copyOf(ORDER))) throw new IllegalStateException("Incomplete template catalogue");
        versions = Map.copyOf(all);
        current = Map.copyOf(latest);
    }

    private static List<Template> load() {
        try (InputStream input = TemplateCatalog.class.getResourceAsStream("/templates/catalogue.json")) {
            if (input == null) throw new IllegalStateException("Missing template catalogue");
            var mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
            JsonNode root = mapper.readTree(input);
            if (!root.isObject() || !root.propertyNames().equals(Set.of("items")) || !root.get("items").isArray()) {
                throw new IllegalStateException("Invalid template catalogue");
            }
            var result = new ArrayList<Template>();
            for (var item : root.get("items")) {
                result.add(mapper.treeToValue(item, Template.class));
            }
            return result;
        } catch (Exception invalid) {
            throw new IllegalStateException("Template catalogue unavailable or invalid", invalid);
        }
    }

    private static Template validate(Template template) {
        if (template == null
            || !ORDER.contains(template.id())
            || template.version() < 1
            || (template.id().equals("blank") && template.version() < 2)) {
            throw new IllegalStateException("Invalid template identity");
        }
        String name = Fields.text(template.name(), "name", 200, true, true);
        if (template.initialization() == null || !template.initialization().equals(new Initialization("CREATOR_ONLY", "PENDING"))) {
            throw new IllegalStateException("Invalid template initialization");
        }
        var categories = new LinkedHashSet<String>();
        for (String category : template.categories()) {
            if (!categories.add(Fields.text(category, "category", 100, false, true))) {
                throw new IllegalStateException("Duplicate template category");
            }
        }
        // The blank template has no categories or tasks; every other template has exactly five starter tasks.
        boolean wrongStarterCount = template.id().equals("blank")
            ? (!categories.isEmpty() || !template.starterTasks().isEmpty())
            : template.starterTasks().size() != 5;
        if (wrongStarterCount) throw new IllegalStateException("Invalid starter count");
        var tasks = new ArrayList<Starter>();
        int index = 0;
        for (Starter row : template.starterTasks()) {
            if (!row.key().equals("task-" + String.format(Locale.ROOT, "%02d", ++index))
                || !categories.contains(row.category())
                || row.dueDate() != null) {
                throw new IllegalStateException("Invalid starter fields");
            }
            tasks.add(new Starter(
                row.key(),
                Fields.text(row.title(), "title", 200, true, true),
                Fields.text(row.description(), "description", 4000, false, false),
                row.category(),
                Fields.points(row.effortPoints(), "effortPoints").toPlainString(),
                null
            ));
        }
        return new Template(
            template.id(),
            name,
            template.version(),
            List.copyOf(categories),
            tasks,
            ProjectRules.validated(template.suggestedSettings()),
            template.initialization()
        );
    }

    /** The newest version of every template, in display order. */
    public List<Template> list() {
        return ORDER.stream().map(current::get).toList();
    }

    /** One template: the newest version when {@code version} is null, else that exact version. Fails with 404. */
    public Template read(String id, Integer version) {
        Template found = version == null ? current.get(id) : versions.get(new Key(id, version));
        if (found == null) throw new ApiFailure(404, "NOT_FOUND", "Resource unavailable");
        return found;
    }

    /**
     * The template to start a new project from: blank when nothing was selected.
     * A selection must name the current version, so a client never creates from a stale one.
     */
    public Template forCreation(Selection selected) {
        Template found = selected == null ? current.get("blank") : current.get(selected.id());
        if (found == null || (selected != null && selected.version() != found.version())) {
            throw new ApiFailure(409, "TEMPLATE_UNAVAILABLE", "Review an available template version");
        }
        return found;
    }

    /** Reads an optional {@code {"id", "version"}} template object from a request body. */
    public static Selection selection(JsonNode node) {
        if (node == null) return null;
        if (node.isNull()) throw ApiFailure.validation("template");
        JsonBody.validate(node, Map.of("id", JsonBody.Kind.STRING, "version", JsonBody.Kind.INTEGER));
        String id = JsonBody.string(node, "id");
        Long version = JsonBody.integer(node, "version");
        if (id == null || id.isBlank() || version == null || version < 1 || version > Integer.MAX_VALUE) {
            throw ApiFailure.validation("template");
        }
        return new Selection(id, version.intValue());
    }
}
