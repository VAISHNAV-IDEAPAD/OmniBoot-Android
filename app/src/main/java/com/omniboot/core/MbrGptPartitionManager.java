package com.omniboot.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

public class MbrGptPartitionManager {
    public static final long FIRST_USABLE_LBA = 2048L;
    private final UsbBlockDevice device;

    public MbrGptPartitionManager(UsbBlockDevice dev) {
        this.device = dev;
    }

    public long[] createGptPartition(String label) throws IOException {
        long totalSectors = device.getTotalSectors();
        long lastLba = totalSectors - 1L;
        long startLba = FIRST_USABLE_LBA;
        long endLba = lastLba - 34L;

        // Protective MBR
        byte[] mbr = new byte[512];
        ByteBuffer bb = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(446);
        bb.put((byte) 0x00);
        bb.put((byte) 0x00);
        bb.put((byte) 0x02);
        bb.put((byte) 0x00);
        bb.put((byte) 0xEE); // GPT Protective
        bb.put((byte) 0xFF);
        bb.put((byte) 0xFF);
        bb.put((byte) 0xFF);
        bb.putInt(1);
        bb.putInt((int) Math.min(totalSectors - 1L, 0xFFFFFFFFL));
        mbr[510] = (byte) 0x55;
        mbr[511] = (byte) 0xAA;
        device.writeSectors(0L, mbr, 0, 512);

        // Partition Entries
        byte[] entries = new byte[32 * 512];
        ByteBuffer ent = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN);
        // Basic Data Partition GUID: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
        putUuid(ent, UUID.fromString("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7"));
        putUuid(ent, UUID.randomUUID());
        ent.putLong(startLba);
        ent.putLong(endLba);
        ent.putLong(0L);
        byte[] nameBytes = label.getBytes("UTF-16LE");
        ent.put(nameBytes, 0, Math.min(nameBytes.length, 72));

        CRC32 crc = new CRC32();
        crc.update(entries);
        long entriesCrc = crc.getValue();

        UUID diskGuid = UUID.randomUUID();
        byte[] priHeader = makeHeader(1L, lastLba, 34L, endLba, diskGuid, 2L, entriesCrc);
        byte[] bakHeader = makeHeader(lastLba, 1L, 34L, endLba, diskGuid, lastLba - 32L, entriesCrc);

        device.writeSectors(1L, priHeader, 0, 512);
        device.writeSectors(2L, entries, 0, entries.length);
        device.writeSectors(lastLba - 32L, entries, 0, entries.length);
        device.writeSectors(lastLba, bakHeader, 0, 512);
        device.sync();

        return new long[] { startLba, endLba - startLba + 1L };
    }

    private byte[] makeHeader(long curLba, long bakLba, long firstUsable, long lastUsable, UUID guid, long entLba, long entCrc) {
        byte[] h = new byte[512];
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(0x5452415020494645L); // "EFI PART"
        b.putInt(0x00010000);
        b.putInt(92);
        b.putInt(0);
        b.putInt(0);
        b.putLong(curLba);
        b.putLong(bakLba);
        b.putLong(firstUsable);
        b.putLong(lastUsable);
        putUuid(b, guid);
        b.putLong(entLba);
        b.putInt(128);
        b.putInt(128);
        b.putInt((int) entCrc);

        CRC32 crc = new CRC32();
        crc.update(h, 0, 92);
        b.position(16);
        b.putInt((int) crc.getValue());
        return h;
    }

    private void putUuid(ByteBuffer b, UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        b.putInt((int) (msb >> 32));
        b.putShort((short) (msb >> 16));
        b.putShort((short) msb);
        ByteBuffer big = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        big.putLong(lsb);
        b.put(big.array());
    }
}
