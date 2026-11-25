package dev.owlibi.reports;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.apache.poi.util.*;

/** Private export ownership, actual live-byte accounting and lock-based abandoned-directory cleanup. */
public final class ReportWorkspace implements AutoCloseable {
    private static final String MARKER="Owlibi professor export v1\n";
    private static final ThreadLocal<ReportWorkspace> CURRENT=new ThreadLocal<>();
    private static final TempFileCreationStrategy FALLBACK=new DefaultTempFileCreationStrategy();
    static {
        TempFile.setTempFileCreationStrategy(new TempFileCreationStrategy() {
            public File createTempFile(String prefix,String suffix) throws IOException {
                var w=CURRENT.get();return w==null?FALLBACK.createTempFile(prefix,suffix):w.file(prefix,suffix).toFile();
            }
            public File createTempDirectory(String prefix) throws IOException {
                if(CURRENT.get()!=null) throw new IOException("Unexpected export directory request");return FALLBACK.createTempDirectory(prefix);
            }
        });
    }
    public static Path defaultParent() { return Path.of(System.getProperty("java.io.tmpdir"),"owlibi-professor-exports-v1"); }
    public final Path directory;
    private final FileChannel owner;
    private final FileLock lock;
    private final ReportBudget deadline;
    private final long quota;
    private long peak;
    private boolean closed;
    private dev.owlibi.web.ApiFailure failure;
    public dev.owlibi.web.ApiFailure failure() { return failure; }
    private dev.owlibi.web.ApiFailure fail(dev.owlibi.web.ApiFailure error) { if(failure==null) failure=error;return error; }
    private void check() { try { deadline.check(); } catch(dev.owlibi.web.ApiFailure error) { throw fail(error); } }
    public boolean closed() { return closed; }
    public ReportWorkspace(ReportBudget deadline) throws IOException { this(defaultParent(),deadline,ReportBudget.WORKSPACE); }
    public ReportWorkspace(Path parent,ReportBudget deadline,long quota) throws IOException {
        this.deadline=deadline;this.quota=quota;
        privateDirectory(parent);directory=Files.createTempDirectory(parent,"export-");
        FileChannel channel=null;FileLock lease=null;
        try {
            privatePermissions(directory,true);
            Files.writeString(directory.resolve("ownership"),MARKER,StandardOpenOption.CREATE_NEW);privatePermissions(directory.resolve("ownership"),false);
            channel=FileChannel.open(directory.resolve("ownership"),StandardOpenOption.WRITE);lease=channel.lock();owner=channel;lock=lease;CURRENT.set(this);measure();
        } catch(IOException | RuntimeException error) {
            CURRENT.remove();
            try {
                if(lease!=null) lease.release();if(channel!=null) channel.close();deleteContents(directory,null);Files.deleteIfExists(directory);closed=true;
            } catch(IOException cleanup) { cleanup.addSuppressed(error);throw new CleanupIncomplete(cleanup); }
            throw error;
        }
    }
    public static final class CleanupIncomplete extends IOException {
        private CleanupIncomplete(Throwable cause) { super("Owned export cleanup incomplete",cause); }
    }

    private static void privateDirectory(Path path) throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(path) || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))) throw new IOException("Unsafe workspace parent");
        Files.createDirectories(path);privatePermissions(path,true);
    }
    private static void privatePermissions(Path path,boolean directory) throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) { posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return; }
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null) throw new IOException("Private permissions unavailable");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
    public Path file(String prefix,String suffix) throws IOException {
        deadline.check();var path=Files.createTempFile(directory,prefix,suffix);privatePermissions(path,false);return path;
    }
    public long measure() throws IOException {
        long total=0;
        try(var files=Files.list(directory)) {
            for(var p:files.toList()) { if(Files.isSymbolicLink(p) || !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected workspace entry");total=Math.addExact(total,Files.size(p)); }
        }
        peak=Math.max(peak,total);if(total>quota) throw fail(ReportBudget.tooLarge());return total;
    }
    public long peakBytes() { return peak; }
    public OutputStream output(Path file,long fileLimit) throws IOException {
        return bounded(Files.newOutputStream(file),fileLimit);
    }
    public OutputStream bounded(OutputStream stream,long fileLimit) {
        return new OutputStream() {
            long written;
            public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            public void write(byte[] bytes,int start,int length) throws IOException {
                for(int at=start;at<start+length;) {
                    int count=Math.min(8192,start+length-at);check();
                    if(count>fileLimit-written || count>quota-measure()) throw fail(ReportBudget.tooLarge());
                    stream.write(bytes,at,count);written+=count;at+=count;measure();
                }
            }
            public void flush() throws IOException { stream.flush();measure(); }
            public void close() throws IOException { stream.close();measure(); }
        };
    }
    public static ReportWorkspace current() { return Objects.requireNonNull(CURRENT.get()); }
    /** Only marker-bearing unlocked directories inside the private parent are abandoned exports. */
    public static void cleanupAbandoned(Path parent) throws IOException {
        if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS)) return;privateDirectory(parent);
        try(var children=Files.list(parent)) {
            for(var directory:children.toList()) {
                if(!directory.getFileName().toString().matches("export-[A-Za-z0-9_-]+") || Files.isSymbolicLink(directory) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) continue;
                var marker=directory.resolve("ownership");
                if(Files.isSymbolicLink(marker) || !Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || Files.size(marker)!=MARKER.length()) continue;
                try(var channel=FileChannel.open(marker,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    try(var lock=channel.tryLock()) {
                        if(lock==null) continue;
                        // Read through the owning handle after locking: Windows enforces byte-range locks.
                        var bytes=java.nio.ByteBuffer.allocate(MARKER.length()+1);
                        while(bytes.hasRemaining() && channel.read(bytes)!=-1) { /* bounded ownership marker */ }
                        if(!java.nio.charset.StandardCharsets.UTF_8.decode(bytes.flip()).toString().equals(MARKER)) continue;
                        deleteContents(directory,marker);
                    }
                    catch(OverlappingFileLockException active) { continue; }
                }
                Files.deleteIfExists(marker);Files.delete(directory);
            }
        }
    }
    private static void deleteContents(Path directory,Path keep) throws IOException {
        try(var files=Files.list(directory)) { for(var p:files.toList()) {
            if(p.equals(keep)) continue;
            // No traversal, recursion or following a link, even if an unexpected entry exists.
            if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected owned subdirectory");Files.deleteIfExists(p);
        } }
    }
    public void close() throws IOException {
        CURRENT.remove();
        try { deleteContents(directory,directory.resolve("ownership")); }
        finally { try { lock.release(); } finally { owner.close(); } }
        Files.deleteIfExists(directory.resolve("ownership"));Files.delete(directory);closed=true;
    }
}
