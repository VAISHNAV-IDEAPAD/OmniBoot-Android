package com.omniboot.core;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UsbMassStorageEngine implements UsbBlockDevice {
    private static final int CBW_SIGNATURE = 0x43425355;
    private static final int CSW_SIGNATURE = 0x53425355;
    private static final int CBW_LENGTH = 31;
    private static final int CSW_LENGTH = 13;

    private static final byte SCSI_TEST_UNIT_READY = 0x00;
    private static final byte SCSI_REQUEST_SENSE = 0x03;
    private static final byte SCSI_READ_CAPACITY_10 = 0x25;
    private static final byte SCSI_WRITE_10 = 0x2A;
    private static final byte SCSI_READ_10 = 0x28;
    private static final byte SCSI_SYNCHRONIZE_CACHE_10 = 0x35;

    public static final int CHUNK_SECTORS = 128; // 64 KB batches

    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint inEndpoint;
    private final UsbEndpoint outEndpoint;

    private int tagCounter = 1;
    private long detectedCapacity = 16L * 1024 * 1024 * 1024;
    private int detectedSectorSize = 512;

    public UsbMassStorageEngine(UsbDeviceConnection conn, UsbInterface iface, UsbEndpoint inEp, UsbEndpoint outEp) {
        this.connection = conn;
        this.usbInterface = iface;
        this.inEndpoint = inEp;
        this.outEndpoint = outEp;
        conn.claimInterface(iface, true);
        initDrive();
    }

    private void initDrive() {
        for (int i = 0; i < 3; i++) {
            if (testUnitReady()) break;
            try { Thread.sleep(100); } catch (Exception ignored) {}
        }
        readCapacity();
    }

    private boolean testUnitReady() {
        try {
            byte[] cdb = new byte[6];
            cdb[0] = SCSI_TEST_UNIT_READY;
            int tag = tagCounter++;
            sendCbw(tag, 0, 0x00, cdb);
            return receiveCsw(tag).status == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void readCapacity() {
        try {
            byte[] cdb = new byte[10];
            cdb[0] = SCSI_READ_CAPACITY_10;
            int tag = tagCounter++;
            sendCbw(tag, 8, 0x80, cdb);
            byte[] buffer = new byte[8];
            int read = connection.bulkTransfer(inEndpoint, buffer, 8, 3000);
            Csw csw = receiveCsw(tag);
            if (csw.status == 0 && read == 8) {
                ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);
                long lastLba = bb.getInt() & 0xFFFFFFFFL;
                detectedSectorSize = bb.getInt();
                if (detectedSectorSize <= 0) detectedSectorSize = 512;
                detectedCapacity = (lastLba + 1L) * detectedSectorSize;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public synchronized int writeSectors(long lba, byte[] data, int offset, int length) throws IOException {
        int numSectors = length / detectedSectorSize;
        int sectorsWritten = 0;
        int currentOffset = offset;
        long currentLba = lba;

        while (sectorsWritten < numSectors) {
            int batch = Math.min(numSectors - sectorsWritten, CHUNK_SECTORS);
            int batchBytes = batch * detectedSectorSize;

            int tag = tagCounter++;
            byte[] cdb = new byte[10];
            cdb[0] = SCSI_WRITE_10;
            cdb[2] = (byte) ((currentLba >> 24) & 0xFF);
            cdb[3] = (byte) ((currentLba >> 16) & 0xFF);
            cdb[4] = (byte) ((currentLba >> 8) & 0xFF);
            cdb[5] = (byte) (currentLba & 0xFF);
            cdb[7] = (byte) ((batch >> 8) & 0xFF);
            cdb[8] = (byte) (batch & 0xFF);

            sendCbw(tag, batchBytes, 0x00, cdb);

            int transferred = 0;
            while (transferred < batchBytes) {
                int slice = Math.min(batchBytes - transferred, 16384);
                int r = connection.bulkTransfer(outEndpoint, data, currentOffset + transferred, slice, 15000);
                if (r < 0) {
                    clearEndpointHalt(outEndpoint);
                    throw new IOException("Bulk write failed at LBA " + currentLba);
                }
                transferred += r;
            }

            Csw csw = receiveCsw(tag);
            if (csw.status != 0) {
                requestSense();
                throw new IOException("SCSI write error at LBA " + currentLba);
            }

            sectorsWritten += batch;
            currentOffset += batchBytes;
            currentLba += batch;
        }

        return length;
    }

    @Override
    public synchronized int readSectors(long lba, byte[] buffer, int offset, int length) throws IOException {
        int sectorCount = length / detectedSectorSize;
        int tag = tagCounter++;
        byte[] cdb = new byte[10];
        cdb[0] = SCSI_READ_10;
        cdb[2] = (byte) ((lba >> 24) & 0xFF);
        cdb[3] = (byte) ((lba >> 16) & 0xFF);
        cdb[4] = (byte) ((lba >> 8) & 0xFF);
        cdb[5] = (byte) (lba & 0xFF);
        cdb[7] = (byte) ((sectorCount >> 8) & 0xFF);
        cdb[8] = (byte) (sectorCount & 0xFF);

        sendCbw(tag, length, 0x80, cdb);

        int transferred = 0;
        while (transferred < length) {
            int slice = Math.min(length - transferred, 16384);
            int r = connection.bulkTransfer(inEndpoint, buffer, offset + transferred, slice, 15000);
            if (r < 0) {
                clearEndpointHalt(inEndpoint);
                throw new IOException("Bulk read failed at LBA " + lba);
            }
            transferred += r;
        }

        Csw csw = receiveCsw(tag);
        if (csw.status != 0) {
            requestSense();
            throw new IOException("SCSI read error at LBA " + lba);
        }
        return transferred;
    }

    @Override
    public synchronized void sync() {
        try {
            int tag = tagCounter++;
            byte[] cdb = new byte[10];
            cdb[0] = SCSI_SYNCHRONIZE_CACHE_10;
            sendCbw(tag, 0, 0x00, cdb);
            receiveCsw(tag);
        } catch (Exception ignored) {}
    }

    private void sendCbw(int tag, int len, int dir, byte[] cdb) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(CBW_SIGNATURE);
        bb.putInt(tag);
        bb.putInt(len);
        bb.put((byte) dir);
        bb.put((byte) 0);
        bb.put((byte) cdb.length);
        bb.put(cdb);
        for (int i = cdb.length; i < 16; i++) bb.put((byte) 0);

        int sent = connection.bulkTransfer(outEndpoint, bb.array(), CBW_LENGTH, 3000);
        if (sent != CBW_LENGTH) {
            throw new IOException("Failed to send CBW to USB drive");
        }
    }

    private Csw receiveCsw(int expectedTag) throws IOException {
        byte[] buf = new byte[CSW_LENGTH];
        int r = connection.bulkTransfer(inEndpoint, buf, CSW_LENGTH, 15000);
        if (r != CSW_LENGTH) {
            clearEndpointHalt(inEndpoint);
            throw new IOException("Failed to receive CSW");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        int sig = bb.getInt();
        int tag = bb.getInt();
        int res = bb.getInt();
        int status = bb.get() & 0xFF;
        if (sig != CSW_SIGNATURE) {
            throw new IOException("Invalid CSW signature");
        }
        return new Csw(sig, tag, res, status);
    }

    private void requestSense() {
        try {
            int tag = tagCounter++;
            byte[] cdb = new byte[6];
            cdb[0] = SCSI_REQUEST_SENSE;
            cdb[4] = 18;
            sendCbw(tag, 18, 0x80, cdb);
            byte[] s = new byte[18];
            connection.bulkTransfer(inEndpoint, s, 18, 3000);
            receiveCsw(tag);
        } catch (Exception ignored) {}
    }

    private void clearEndpointHalt(UsbEndpoint ep) {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, ep.getAddress(), null, 0, 3000);
        } catch (Exception ignored) {}
    }

    @Override
    public long getTotalCapacityBytes() { return detectedCapacity; }
    @Override
    public int getSectorSize() { return detectedSectorSize; }
    @Override
    public long getTotalSectors() { return detectedSectorSize > 0 ? detectedCapacity / detectedSectorSize : 0L; }

    @Override
    public void close() {
        sync();
        try { connection.releaseInterface(usbInterface); } catch (Exception ignored) {}
        try { connection.close(); } catch (Exception ignored) {}
    }

    private static class Csw {
        final int signature, tag, residue, status;
        Csw(int s, int t, int r, int st) { signature = s; tag = t; residue = r; status = st; }
    }
}
