package dev.owlibi.reports;

import dev.owlibi.web.ApiFailure;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.apache.poi.util.TempFileCreationStrategy;

/** Private export ownership, actual live-byte accounting and lock-based abandoned-directory cleanup. */
public final class ReportWorkspace implements AutoCloseable {
    private static final String MARKER = "Owlibi professor export v1\n";
    private static final ThreadLocal<ReportWorkspace> CURRENT = new ThreadLocal<>();
    private static final TempFileCreationStrategy FALLBACK = new DefaultTempFileCreationStrategy();

    static {
        // Apache POI asks this strategy for its temporary files. While a workspace is open on this thread,
        // they go inside that workspace so they are counted and cleaned up with it.
        TempFile.setTempFileCreationStrategy(new TempFileCreationStrategy() {
            @Override
            public File createTempFile(String prefix, String suffix) throws IOException {
                var workspace = CURRENT.get();
                return workspace == null ? FALLBACK.createTempFile(prefix, suffix) : workspace.file(prefix, suffix).toFile();
            }

            @Override
            public File createTempDirectory(String prefix) throws IOException {
                if (CURRENT.get() != null) throw new IOException("Unexpected export directory request");
                return FALLBACK.createTempDirectory(prefix);
            }
        });
    }

    /** The shared parent folder for all export workspaces, inside the system temporary folder. */
    public static Path defaultParent() {
        return Path.of(System.getProperty("java.io.tmpdir"), "owlibi-professor-exports-v1");
    }

    /** This workspace's own private folder. */
    public final Path directory;
    private final FileChannel owner;
    private final FileLock lock;
    private final ReportBudget deadline;
    private final long quota;
    private long peak;
    private boolean closed;
    private ApiFailure failure;

    /** The first budget failure this workspace raised, or {@code null}. */
    public ApiFailure failure() {
        return failure;
    }

    private ApiFailure fail(ApiFailure error) {
        if (failure == null) failure = error;
        return error;
    }

    private void check() {
        try {
            deadline.check();
        } catch (ApiFailure error) {
            throw fail(error);
        }
    }

    /** Whether the workspace folder has been fully removed. */
    public boolean closed() {
        return closed;
    }

    /** Opens a workspace in the default parent with the standard size quota. */
    public ReportWorkspace(ReportBudget deadline) throws IOException {
        this(defaultParent(), deadline, ReportBudget.WORKSPACE);
    }

    /**
     * Creates a private folder under {@code parent}, writes and locks an ownership marker in it,
     * and makes it the current workspace for this thread.
     */
    public ReportWorkspace(Path parent, ReportBudget deadline, long quota) throws IOException {
        this.deadline = deadline;
        this.quota = quota;
        privateDirectory(parent);
        directory = Files.createTempDirectory(parent, "export-");
        FileChannel channel = null;
        FileLock lease = null;
        try {
            privatePermissions(directory, true);
            Files.writeString(directory.resolve("ownership"), MARKER, StandardOpenOption.CREATE_NEW);
            privatePermissions(directory.resolve("ownership"), false);
            channel = FileChannel.open(directory.resolve("ownership"), StandardOpenOption.WRITE);
            lease = channel.lock();
            owner = channel;
            lock = lease;
            CURRENT.set(this);
            measure();
        } catch (IOException | RuntimeException error) {
            CURRENT.remove();
            try {
                if (lease != null) lease.release();
                if (channel != null) channel.close();
                deleteContents(directory, null);
                Files.deleteIfExists(directory);
                closed = true;
            } catch (IOException cleanup) {
                cleanup.addSuppressed(error);
                throw new CleanupIncomplete(cleanup);
            }
            throw error;
        }
    }

    /** Thrown when a failed workspace could not delete its own folder, so the folder may still be on disk. */
    public static final class CleanupIncomplete extends IOException {
        private CleanupIncomplete(Throwable cause) {
            super("Owned export cleanup incomplete", cause);
        }
    }

    private static void privateDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe workspace parent");
        }
        Files.createDirectories(path);
        privatePermissions(path, true);
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Private permissions unavailable");
        acl.setAcl(List.of(AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(acl.getOwner())
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build()));
    }

    /** Creates a new owner-only temporary file inside this workspace, after checking the time budget. */
    public Path file(String prefix, String suffix) throws IOException {
        deadline.check();
        var path = Files.createTempFile(directory, prefix, suffix);
        privatePermissions(path, false);
        return path;
    }

    /** Adds up the bytes on disk in this workspace, records the peak and fails when the quota is exceeded. */
    public long measure() throws IOException {
        long total = 0;
        try (var files = Files.list(directory)) {
            for (var entry : files.toList()) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected workspace entry");
                }
                total = Math.addExact(total, Files.size(entry));
            }
        }
        peak = Math.max(peak, total);
        if (total > quota) throw fail(ReportBudget.tooLarge());
        return total;
    }

    /** The largest total size this workspace has reached. */
    public long peakBytes() {
        return peak;
    }

    /** Opens a file for writing, limited to {@code fileLimit} bytes and to the workspace quota. */
    public OutputStream output(Path file, long fileLimit) throws IOException {
        return bounded(Files.newOutputStream(file), fileLimit);
    }

    /**
     * Wraps a stream so every write checks the time budget, the per-file limit and the workspace quota,
     * writing in chunks of at most 8 KiB.
     */
    public OutputStream bounded(OutputStream stream, long fileLimit) {
        return new OutputStream() {
            long written;

            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int start, int length) throws IOException {
                for (int at = start; at < start + length;) {
                    int count = Math.min(8192, start + length - at);
                    check();
                    if (count > fileLimit - written || count > quota - measure()) throw fail(ReportBudget.tooLarge());
                    stream.write(bytes, at, count);
                    written += count;
                    at += count;
                    measure();
                }
            }

            @Override
            public void flush() throws IOException {
                stream.flush();
                measure();
            }

            @Override
            public void close() throws IOException {
                stream.close();
                measure();
            }
        };
    }

    /** The workspace open on this thread; fails if there is none. */
    public static ReportWorkspace current() {
        return Objects.requireNonNull(CURRENT.get());
    }

    /** Only marker-bearing unlocked directories inside the private parent are abandoned exports. */
    public static void cleanupAbandoned(Path parent) throws IOException {
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return;
        privateDirectory(parent);
        try (var children = Files.list(parent)) {
            for (var directory : children.toList()) {
                if (!directory.getFileName().toString().matches("export-[A-Za-z0-9_-]+")
                    || Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var marker = directory.resolve("ownership");
                if (Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) != MARKER.length()) {
                    continue;
                }
                try (var channel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    try (var lock = channel.tryLock()) {
                        if (lock == null) continue;
                        // Read through the owning handle after locking: Windows enforces byte-range locks.
                        var bytes = ByteBuffer.allocate(MARKER.length() + 1);
                        while (bytes.hasRemaining() && channel.read(bytes) != -1) {
                            /* bounded ownership marker */
                        }
                        if (!StandardCharsets.UTF_8.decode(bytes.flip()).toString().equals(MARKER)) continue;
                        deleteContents(directory, marker);
                    } catch (OverlappingFileLockException active) {
                        continue;
                    }
                }
                Files.deleteIfExists(marker);
                Files.delete(directory);
            }
        }
    }

    private static void deleteContents(Path directory, Path keep) throws IOException {
        try (var files = Files.list(directory)) {
            for (var entry : files.toList()) {
                if (entry.equals(keep)) continue;
                // No traversal, recursion or following a link, even if an unexpected entry exists.
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected owned subdirectory");
                Files.deleteIfExists(entry);
            }
        }
    }

    /** Deletes every file, releases the ownership lock and removes the folder. */
    @Override
    public void close() throws IOException {
        CURRENT.remove();
        try {
            deleteContents(directory, directory.resolve("ownership"));
        } finally {
            try {
                lock.release();
            } finally {
                owner.close();
            }
        }
        Files.deleteIfExists(directory.resolve("ownership"));
        Files.delete(directory);
        closed = true;
    }
}
