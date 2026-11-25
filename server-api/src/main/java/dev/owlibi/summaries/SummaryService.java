package dev.owlibi.summaries;

import dev.owlibi.snapshots.ProjectSnapshot;
import dev.owlibi.snapshots.SnapshotService;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Takes a snapshot of the project and hands it to {@link SummaryCalculator}. */
@Service
public class SummaryService {
    private final SnapshotService snapshots;

    public SummaryService(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    /** The contribution summary for a member of the project. */
    public SummaryDtos.Contribution contribution(UUID actor, UUID project) {
        return SummaryCalculator.contribution(capture(actor, project));
    }

    /** The workload summary for a member of the project. */
    public SummaryDtos.Workload workload(UUID actor, UUID project) {
        return SummaryCalculator.workload(capture(actor, project));
    }

    /** A database failure during the snapshot becomes a 503 summary error. */
    private ProjectSnapshot capture(UUID actor, UUID project) {
        try {
            return snapshots.capture(actor, project);
        } catch (DataAccessException invalid) {
            throw SummaryCalculator.unavailable();
        }
    }
}
