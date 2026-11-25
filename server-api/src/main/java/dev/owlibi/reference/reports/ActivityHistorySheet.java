package dev.owlibi.reference.reports;

import dev.owlibi.reference.snapshots.ProjectSnapshot;
import java.util.Locale;

/** The complete captured activity history, oldest first. */
final class ActivityHistorySheet extends ReportSheet {
    ActivityHistorySheet(ProjectSnapshot snapshot) {
        super("Activity history", snapshot, new int[] {25, 23, 26, 39, 66, 27, 39, 39, 39, 10}, 6, 7, 8);
    }

    @Override
    protected String subtitle() {
        return "Complete captured history, oldest first. Read split payloads in Part order.";
    }

    @Override
    protected void writeRows() {
        int eventHeader = header("Occurred UTC", "Action", "Actor", "Reason", "Safe change summary", "Event type",
            "Task UUID", "Actor UUID", "Event UUID", "Part");
        for (ProjectSnapshot.Event event : snapshot().history().events()) {
            row(event.occurredAtUtc(),
                eventAction(event.eventType()),
                event.actorName(),
                event.reason(),
                event.changeSummary(),
                event.eventType(),
                event.taskId(),
                event.actorId(),
                event.id());
        }
        filter(eventHeader, 10);
    }

    /** The readable name of an event type, for example {@code "Task approved"} for {@code APPROVE}. */
    private static String eventAction(String type) {
        return switch (type) {
            case "START" -> "Task started";
            case "SUBMIT_REVIEW" -> "Review requested";
            case "APPROVE" -> "Task approved";
            case "RETURN" -> "Returned for changes";
            case "REOPEN" -> "Task reopened";
            case "CANCEL" -> "Task cancelled";
            default -> {
                String label = type.toLowerCase(Locale.ROOT).replace('_', ' ');
                yield label.isEmpty() ? label : Character.toUpperCase(label.charAt(0)) + label.substring(1);
            }
        };
    }
}
