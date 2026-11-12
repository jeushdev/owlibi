package dev.owlibi.reference.projects;

import dev.owlibi.reference.web.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Typed project rules; examples explain estimates, never restrict task agreement values. */
public final class ProjectRules {
    private ProjectRules() {}
    public record Example(String points,String label) {}
    public record Values(String effortScaleName,List<Example> effortScaleExamples,String overloadThreshold,int nearDueDays) {
        public Values { effortScaleExamples=List.copyOf(effortScaleExamples); }
    }
    public record Settings(UUID projectId,boolean configured,String settingsVersion,String projectZone,
        String effortScaleName,List<Example> effortScaleExamples,String overloadThreshold,Integer nearDueDays,Instant updatedAtUtc) {
        public Values values() { return configured?new Values(effortScaleName,effortScaleExamples,overloadThreshold,nearDueDays):null; }
    }
    public static final Map<String,JsonBody.Kind> VALUES=Map.of("effortScaleName",JsonBody.Kind.STRING,
        "effortScaleExamples",JsonBody.Kind.OBJECT_ARRAY,"overloadThreshold",JsonBody.Kind.STRING,"nearDueDays",JsonBody.Kind.INTEGER);
    public static final Map<String,JsonBody.Kind> PATCH;
    public static final List<String> ORDER=List.of("effortScaleName","effortScaleExamples","overloadThreshold","nearDueDays");
    static { var fields=new HashMap<>(VALUES); fields.put("settingsVersion",JsonBody.Kind.STRING); PATCH=Map.copyOf(fields); }
    public static Values normalize(JsonNode input,Values previous) {
        JsonBody.validate(input,PATCH);
        for(String field:ORDER) if ((previous==null && !input.has(field)) || (input.has(field) && input.get(field).isNull())) throw ApiFailure.validation(field);
        String name=input.has("effortScaleName")?Fields.text(JsonBody.string(input,"effortScaleName"),"effortScaleName",100,true,true):previous.effortScaleName();
        String threshold=input.has("overloadThreshold")?Fields.points(JsonBody.string(input,"overloadThreshold"),"overloadThreshold").toPlainString():previous.overloadThreshold();
        int days=previous==null?0:previous.nearDueDays();
        if (input.has("nearDueDays")) {
            long value=JsonBody.integer(input,"nearDueDays");
            if (value<0 || value>30) throw ApiFailure.validation("nearDueDays"); days=(int)value;
        }
        List<Example> examples=previous==null?null:previous.effortScaleExamples();
        if (input.has("effortScaleExamples")) {
            var array=input.get("effortScaleExamples");
            if (array.isEmpty() || array.size()>10) throw ApiFailure.validation("effortScaleExamples");
            var values=new ArrayList<Example>(); var seen=new HashSet<String>();
            for(var item:array) {
                JsonBody.validate(item,Map.of("points",JsonBody.Kind.STRING,"label",JsonBody.Kind.STRING));
                String p=Fields.points(JsonBody.string(item,"points"),"effortScaleExamples").toPlainString();
                String label=Fields.text(JsonBody.string(item,"label"),"effortScaleExamples",100,true,true);
                if (!seen.add(p)) throw ApiFailure.validation("effortScaleExamples"); values.add(new Example(p,label));
            }
            examples=List.copyOf(values);
        }
        return new Values(name,examples,threshold,days);
    }
    public static Values validated(Values value) {
        if (value==null) throw ApiFailure.validation("settings");
        return normalize(JsonMapper.shared().valueToTree(value),null);
    }
    public static long version(String value) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw ApiFailure.validation("settingsVersion");
        try { return Long.parseLong(value); } catch(NumberFormatException invalid) { throw ApiFailure.validation("settingsVersion"); }
    }
    public static Map<String,Object> summary(Settings value) {
        var result=new LinkedHashMap<String,Object>();
        result.put("configured",value.configured()); result.put("settingsVersion",value.settingsVersion());
        result.put("effortScaleName",value.effortScaleName()); result.put("effortScaleExamples",value.effortScaleExamples());
        result.put("overloadThreshold",value.overloadThreshold()); result.put("nearDueDays",value.nearDueDays()); return result;
    }
}
