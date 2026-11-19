package dev.owlibi.reference.summaries;

import dev.owlibi.reference.snapshots.*;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {
    private final SnapshotService snapshots;
    public SummaryService(SnapshotService snapshots) { this.snapshots=snapshots; }
    private ProjectSnapshot capture(UUID actor,UUID project) {
        try { return snapshots.capture(actor,project); }
        catch(DataAccessException invalid) { throw SummaryCalculator.unavailable(); }
    }
    public SummaryDtos.Contribution contribution(UUID actor,UUID project) { return SummaryCalculator.contribution(capture(actor,project)); }
    public SummaryDtos.Workload workload(UUID actor,UUID project) { return SummaryCalculator.workload(capture(actor,project)); }
}
