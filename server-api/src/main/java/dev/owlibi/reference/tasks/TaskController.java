package dev.owlibi.reference.tasks;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.JsonBody;
import static dev.owlibi.reference.web.JsonBody.Kind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/projects/{project}",produces="application/json")
public class TaskController {
    private final TaskService tasks;
    private final JsonBody bodies;
    public TaskController(TaskService tasks,JsonBody bodies) { this.tasks=tasks; this.bodies=bodies; }
    @PostMapping("/tasks") public ResponseEntity<TaskDtos.Task> create(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,HttpServletRequest request) {
        tasks.requireLeader(actor.id(),project);
        var body=bodies.readTyped(request,Map.of("title",STRING,"description",STRING,"category",STRING,"dueDate",STRING,"effortPoints",STRING,"assigneeIds",STRING_ARRAY));
        return ResponseEntity.status(201).body(tasks.create(actor.id(),project,JsonBody.string(body,"title"),JsonBody.string(body,"description"),
            JsonBody.string(body,"category"),JsonBody.string(body,"dueDate"),JsonBody.string(body,"effortPoints"),JsonBody.strings(body,"assigneeIds")));
    }
    @PatchMapping("/tasks/{task}") public TaskDtos.Task edit(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,@PathVariable("task") UUID task,HttpServletRequest request) {
        tasks.requireLeader(actor.id(),project);
        var body=bodies.readTyped(request,Map.of("version",INTEGER,"reason",STRING,"title",STRING,"description",STRING,
            "category",STRING,"dueDate",STRING,"effortPoints",STRING,"assigneeIds",STRING_ARRAY));
        return tasks.edit(actor.id(),project,task,new TaskPatch(JsonBody.integer(body,"version"),JsonBody.string(body,"reason"),
            new TaskPatch.Field<>(body.has("title"),JsonBody.string(body,"title")),
            new TaskPatch.Field<>(body.has("description"),JsonBody.string(body,"description")),
            new TaskPatch.Field<>(body.has("category"),JsonBody.string(body,"category")),
            new TaskPatch.Field<>(body.has("dueDate"),JsonBody.string(body,"dueDate")),
            new TaskPatch.Field<>(body.has("effortPoints"),JsonBody.string(body,"effortPoints")),
            new TaskPatch.Field<>(body.has("assigneeIds"),JsonBody.strings(body,"assigneeIds"))));
    }
    @PostMapping("/tasks/{task}/acceptance") public TaskDtos.Task accept(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,@PathVariable("task") UUID task,HttpServletRequest request) {
        tasks.requireMember(actor.id(),project);
        var body=bodies.readTyped(request,Map.of("agreementVersion",INTEGER));
        return tasks.accept(actor.id(),project,task,JsonBody.integer(body,"agreementVersion"));
    }
    @PostMapping("/tasks/{task}/transitions") public TaskDtos.Task transition(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,@PathVariable("task") UUID task,HttpServletRequest request) {
        tasks.requireMember(actor.id(),project);
        var body=bodies.readTyped(request,Map.of("action",STRING,"version",INTEGER,"reason",STRING));
        return tasks.transition(actor.id(),project,task,JsonBody.string(body,"action"),JsonBody.integer(body,"version"),JsonBody.string(body,"reason"));
    }
    @GetMapping("/board") public TaskDtos.Board board(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,HttpServletRequest request) {
        bodies.empty(request); return tasks.board(actor.id(),project);
    }
    @GetMapping("/activity") public TaskDtos.Activity activity(@AuthenticationPrincipal AccountPrincipal actor,@PathVariable("project") UUID project,
        @RequestParam(name="limit",required=false) String limit,@RequestParam(name="cursor",required=false) String cursor,HttpServletRequest request) {
        bodies.empty(request); return tasks.activity(actor.id(),project,limit,cursor);
    }
}
