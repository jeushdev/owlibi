package dev.owlibi.reference.reports;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Deliberate corruption of a freshly generated synthetic archive, never a production input. */
final class WorkbookFaults {
    private WorkbookFaults() {}
    static int directoryOffset(byte[] bytes) throws IOException {
        for(int n=bytes.length-22;n>=Math.max(0,bytes.length-65557);n--)
            if(bytes[n]==0x50 && bytes[n+1]==0x4b && bytes[n+2]==5 && bytes[n+3]==6)
                return ByteBuffer.wrap(bytes,n+16,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        throw new IOException("Synthetic archive has no end record");
    }
    static void removeDirectory(Path path) throws IOException {
        var bytes=Files.readAllBytes(path);Files.write(path,Arrays.copyOf(bytes,directoryOffset(bytes)));
    }
}
