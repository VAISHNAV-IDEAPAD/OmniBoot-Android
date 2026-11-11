package com.omniboot.core;

import java.io.Closeable;
import java.io.IOException;

public interface UsbBlockDevice extends Closeable {
    long getTotalCapacityBytes();
    int getSectorSize();
    long getTotalSectors();
    int writeSectors(long lba, byte[] data, int offset, int length) throws IOException;
    int readSectors(long lba, byte[] buffer, int offset, int length) throws IOException;
    void sync() throws IOException;
}
