package dev.owlibi.reference.support;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;

/** Test source only. Pause AFTER the board's first real SQL read, with its transaction still open. */
public class BoardSnapshotProbe extends JdbcTemplate {
    public final AtomicBoolean armed=new AtomicBoolean();
    public final CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
    public volatile int isolation;
    public volatile boolean readOnly;
    public BoardSnapshotProbe(DataSource source) { super(source); }
    @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
        List<T> rows=super.query(sql,mapper,args);
        if (sql.contains("WHERE p.id=? AND m.user_id=?") && armed.compareAndSet(true,false)) {
            execute((ConnectionCallback<Void>) c -> { isolation=c.getTransactionIsolation(); readOnly=c.isReadOnly(); return null; });
            read.countDown();
            try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Snapshot probe timeout"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        }
        return rows;
    }
    public static class Configuration {
        @Bean @Primary public BoardSnapshotProbe snapshotJdbc(DataSource source) { return new BoardSnapshotProbe(source); }
    }
    public static final String SOURCE="--spring.main.sources=dev.owlibi.reference.support.MutableClock$Configuration,dev.owlibi.reference.support.BoardSnapshotProbe$Configuration";
}
