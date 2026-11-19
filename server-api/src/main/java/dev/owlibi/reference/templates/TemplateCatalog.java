package dev.owlibi.reference.templates;

import dev.owlibi.reference.projects.ProjectRules;
import dev.owlibi.reference.web.*;
import java.io.InputStream;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/** Immutable versioned data, not executable template policy or Task subclasses. */
@Component
public class TemplateCatalog {
    public record Starter(String key,String title,String description,String category,String effortPoints,String dueDate) {}
    public record Initialization(String assigneePolicy,String acceptancePolicy) {}
    public record Template(String id,String name,int version,List<String> categories,List<Starter> starterTasks,
        ProjectRules.Values suggestedSettings,Initialization initialization) {
        public Template { categories=List.copyOf(categories); starterTasks=List.copyOf(starterTasks); }
    }
    public record Ref(String id,int version) {}
    public record Selection(String id,Integer version) {}
    private record Key(String id,int version) {}
    private static final List<String> ORDER=List.of("blank","coding","written","reporting-presentation","filming-video");
    private final Map<Key,Template> versions;
    private final Map<String,Template> current;
    public TemplateCatalog() { this(load()); }
    public TemplateCatalog(List<Template> source) {
        var all=new HashMap<Key,Template>(); var latest=new HashMap<String,Template>();
        for(Template value:source) {
            Template normalized=validate(value);
            if (all.put(new Key(normalized.id(),normalized.version()),normalized)!=null) throw new IllegalStateException("Duplicate template identity");
            latest.merge(normalized.id(),normalized,(a,b) -> a.version()>b.version()?a:b);
        }
        if (!latest.keySet().equals(Set.copyOf(ORDER))) throw new IllegalStateException("Incomplete template catalogue");
        versions=Map.copyOf(all); current=Map.copyOf(latest);
    }
    private static List<Template> load() {
        try(InputStream input=TemplateCatalog.class.getResourceAsStream("/templates/catalogue.json")) {
            if (input==null) throw new IllegalStateException("Missing template catalogue");
            var mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            JsonNode root=mapper.readTree(input);
            if (!root.isObject() || !root.propertyNames().equals(Set.of("items")) || !root.get("items").isArray()) throw new IllegalStateException("Invalid template catalogue");
            var result=new ArrayList<Template>(); for(var item:root.get("items")) result.add(mapper.treeToValue(item,Template.class)); return result;
        } catch(Exception invalid) { throw new IllegalStateException("Template catalogue unavailable or invalid",invalid); }
    }
    private static Template validate(Template t) {
        if (t==null || !ORDER.contains(t.id()) || t.version()<1 || (t.id().equals("blank") && t.version()<2)) throw new IllegalStateException("Invalid template identity");
        String name=Fields.text(t.name(),"name",200,true,true);
        if (t.initialization()==null || !t.initialization().equals(new Initialization("CREATOR_ONLY","PENDING"))) throw new IllegalStateException("Invalid template initialization");
        var categories=new LinkedHashSet<String>();
        for(String c:t.categories()) if (!categories.add(Fields.text(c,"category",100,false,true))) throw new IllegalStateException("Duplicate template category");
        if (t.id().equals("blank")?(!categories.isEmpty() || !t.starterTasks().isEmpty()):t.starterTasks().size()!=5) throw new IllegalStateException("Invalid starter count");
        var tasks=new ArrayList<Starter>(); int index=0;
        for(Starter row:t.starterTasks()) {
            if (!row.key().equals("task-"+String.format(Locale.ROOT,"%02d",++index)) || !categories.contains(row.category()) || row.dueDate()!=null) throw new IllegalStateException("Invalid starter fields");
            tasks.add(new Starter(row.key(),Fields.text(row.title(),"title",200,true,true),Fields.text(row.description(),"description",4000,false,false),
                row.category(),Fields.points(row.effortPoints(),"effortPoints").toPlainString(),null));
        }
        return new Template(t.id(),name,t.version(),List.copyOf(categories),tasks,ProjectRules.validated(t.suggestedSettings()),t.initialization());
    }
    public List<Template> list() { return ORDER.stream().map(current::get).toList(); }
    public Template read(String id,Integer version) {
        Template found=version==null?current.get(id):versions.get(new Key(id,version));
        if (found==null) throw new ApiFailure(404,"NOT_FOUND","Resource unavailable"); return found;
    }
    public Template forCreation(Selection selected) {
        Template found=selected==null?current.get("blank"):current.get(selected.id());
        if (found==null || (selected!=null && selected.version()!=found.version())) throw new ApiFailure(409,"TEMPLATE_UNAVAILABLE","Review an available template version");
        return found;
    }
    public static Selection selection(JsonNode node) {
        if (node==null) return null;
        if (node.isNull()) throw ApiFailure.validation("template");
        JsonBody.validate(node,Map.of("id",JsonBody.Kind.STRING,"version",JsonBody.Kind.INTEGER));
        String id=JsonBody.string(node,"id"); Long version=JsonBody.integer(node,"version");
        if (id==null || id.isBlank() || version==null || version<1 || version>Integer.MAX_VALUE) throw ApiFailure.validation("template");
        return new Selection(id,version.intValue());
    }
}
