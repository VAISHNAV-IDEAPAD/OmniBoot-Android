package com.omniboot.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WimSplitter {
    public static final int WIM_HEADER_SIZE = 208;
    public static final String WIM_MAGIC = "MSWIM\0\0\0";
    public static final int FLAG_HEADER_SPANNED = 0x00000008;

    private final long maxChunkSize;

    public WimSplitter(long maxChunkSizeBytes) {
        this.maxChunkSize = maxChunkSizeBytes;
    }

    public interface ChunkOutputProvider {
        OutputStream getOutputStream(int partNumber) throws Exception;
    }

    public interface ProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    public void split(InputStream in, long totalWimSize, ChunkOutputProvider provider, ProgressListener listener) throws Exception {
        int totalParts = (int) Math.max(1, (totalWimSize + maxChunkSize - 1) / maxChunkSize);
        byte[] header = new byte[WIM_HEADER_SIZE];
        int read = 0;
        while (read < WIM_HEADER_SIZE) {
            int r = in.read(header, read, WIM_HEADER_SIZE - read);
            if (r < 0) break;
            read += r;
        }

        String magic = new String(header, 0, 8, "US-ASCII");
        boolean isWim = magic.equals(WIM_MAGIC);

        long processed = 0L;
        byte[] buffer = new byte[64 * 1024];

        for (int part = 1; part <= totalParts; part++) {
            long target = (part == totalParts) ? (totalWimSize - processed) : maxChunkSize;
            long written = 0L;

            try (OutputStream out = provider.getOutputStream(part)) {
                if (isWim) {
                    byte[] patched = header.clone();
                    ByteBuffer bb = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN);
                    int flags = bb.getInt(16) | FLAG_HEADER_SPANNED;
                    bb.putInt(16, flags);
                    bb.putShort(40, (short) part);
                    bb.putShort(42, (short) totalParts);
                    out.write(patched);
                    written += WIM_HEADER_SIZE;
                    processed += WIM_HEADER_SIZE;
                }

                while (written < target) {
                    int toRead = (int) Math.min((long) buffer.length, target - written);
                    int r = in.read(buffer, 0, toRead);
                    if (r < 0) break;
                    out.write(buffer, 0, r);
                    written += r;
                    processed += r;
                    if (listener != null) listener.onProgress(processed, totalWimSize);
                }
                out.flush();
            }
        }
    }
}
