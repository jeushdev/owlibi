package dev.owlibi.reference.tasks;

import static dev.owlibi.reference.web.JsonBody.Kind.INTEGER;
import static dev.owlibi.reference.web.JsonBody.Kind.STRING;
import static dev.owlibi.reference.web.JsonBody.Kind.STRING_ARRAY;

import dev.owlibi.reference.security.AccountPrincipal;
import dev.owlibi.reference.web.JsonBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** HTTP endpoints for a project's tasks, board and activity log. The rules live in {@link TaskService}. */
@RestController
@RequestMapping(value = "/api/v1/projects/{project}", produces = "application/json")
public class TaskController {
    private static final Map<String, JsonBody.Kind> CREATE_FIELDS = Map.of(
        "title", STRING,
        "description", STRING,
        "category", STRING,
        "dueDate", STRING,
        "effortPoints", STRING,
        "assigneeIds", STRING_ARRAY);
    private static final Map<String, JsonBody.Kind> EDIT_FIELDS = Map.of(
        "version", INTEGER,
        "reason", STRING,
        "title", STRING,
        "description", STRING,
        "category", STRING,
        "dueDate", STRING,
        "effortPoints", STRING,
        "assigneeIds", STRING_ARRAY);
    private static final Map<String, JsonBody.Kind> ACCEPTANCE_FIELDS = Map.of("agreementVersion", INTEGER);
    private static final Map<String, JsonBody.Kind> TRANSITION_FIELDS = Map.of(
        "action", STRING,
        "version", INTEGER,
        "reason", STRING);

    private final TaskService tasks;
    private final JsonBody bodies;

    public TaskController(TaskService tasks, JsonBody bodies) {
        this.tasks = tasks;
        this.bodies = bodies;
    }

    /** Creates a task. Leader only. */
    @PostMapping("/tasks")
    public ResponseEntity<TaskDtos.Task> create(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            HttpServletRequest request) {
        tasks.requireLeader(actor.id(), project);
        var body = bodies.readTyped(request, CREATE_FIELDS);
        var created = tasks.create(actor.id(), project,
            JsonBody.string(body, "title"),
            JsonBody.string(body, "description"),
            JsonBody.string(body, "category"),
            JsonBody.string(body, "dueDate"),
            JsonBody.string(body, "effortPoints"),
            JsonBody.strings(body, "assigneeIds"));
        return ResponseEntity.status(201).body(created);
    }

    /** Edits some fields of a task. Leader only. */
    @PatchMapping("/tasks/{task}")
    public TaskDtos.Task edit(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            @PathVariable("task") UUID task,
            HttpServletRequest request) {
        tasks.requireLeader(actor.id(), project);
        var body = bodies.readTyped(request, EDIT_FIELDS);
        var patch = new TaskPatch(
            JsonBody.integer(body, "version"),
            JsonBody.string(body, "reason"),
            textField(body, "title"),
            textField(body, "description"),
            textField(body, "category"),
            textField(body, "dueDate"),
            textField(body, "effortPoints"),
            new TaskPatch.Field<>(body.has("assigneeIds"), JsonBody.strings(body, "assigneeIds")));
        return tasks.edit(actor.id(), project, task, patch);
    }

    private static TaskPatch.Field<String> textField(JsonNode body, String name) {
        return new TaskPatch.Field<>(body.has(name), JsonBody.string(body, name));
    }

    /** The caller, an assignee, accepts the task's current agreement. */
    @PostMapping("/tasks/{task}/acceptance")
    public TaskDtos.Task accept(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            @PathVariable("task") UUID task,
            HttpServletRequest request) {
        tasks.requireMember(actor.id(), project);
        var body = bodies.readTyped(request, ACCEPTANCE_FIELDS);
        return tasks.accept(actor.id(), project, task, JsonBody.integer(body, "agreementVersion"));
    }

    /** Moves a task to another status by an action such as {@code START}. */
    @PostMapping("/tasks/{task}/transitions")
    public TaskDtos.Task transition(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            @PathVariable("task") UUID task,
            HttpServletRequest request) {
        tasks.requireMember(actor.id(), project);
        var body = bodies.readTyped(request, TRANSITION_FIELDS);
        return tasks.transition(actor.id(), project, task,
            JsonBody.string(body, "action"),
            JsonBody.integer(body, "version"),
            JsonBody.string(body, "reason"));
    }

    /** The project's board: members and every task. */
    @GetMapping("/board")
    public TaskDtos.Board board(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            HttpServletRequest request) {
        bodies.empty(request);
        return tasks.board(actor.id(), project);
    }

    /** The project's activity log, newest first. */
    @GetMapping("/activity")
    public TaskDtos.Activity activity(
            @AuthenticationPrincipal AccountPrincipal actor,
            @PathVariable("project") UUID project,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletRequest request) {
        bodies.empty(request);
        return tasks.activity(actor.id(), project, limit, cursor);
    }
}
