package com.omniboot.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public class Fat32Formatter {
    private final UsbBlockDevice device;

    public Fat32Formatter(UsbBlockDevice dev) {
        this.device = dev;
    }

    public void format(long startLba, long sectorCount, String label) throws IOException {
        int spc = (sectorCount < 16777216L) ? 8 : (sectorCount < 33554432L) ? 16 : 32;
        int reserved = 32;
        int numFats = 2;

        long approxClusters = (sectorCount - reserved) / spc;
        long fatBytes = (approxClusters + 2) * 4;
        int sectorsPerFat = (int) ((fatBytes + 511) / 512);

        byte[] vbr = new byte[512];
        ByteBuffer bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 0xEB);
        bb.put((byte) 0x58);
        bb.put((byte) 0x90);
        bb.put("MSWIN4.1".getBytes("US-ASCII"));
        bb.putShort((short) 512);
        bb.put((byte) spc);
        bb.putShort((short) reserved);
        bb.put((byte) numFats);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.put((byte) 0xF8);
        bb.putShort((short) 0);
        bb.putShort((short) 63);
        bb.putShort((short) 255);
        bb.putInt((int) startLba);
        bb.putInt((int) sectorCount);
        bb.putInt(sectorsPerFat);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putInt(2); // Root cluster
        bb.putShort((short) 1); // FSInfo
        bb.putShort((short) 6); // Backup boot
        bb.put(new byte[12]);
        bb.put((byte) 0x80);
        bb.put((byte) 0);
        bb.put((byte) 0x29);
        bb.putInt(new Random().nextInt());

        String paddedLabel = String.format("%-11s", label.length() > 11 ? label.substring(0, 11) : label);
        bb.put(paddedLabel.getBytes("US-ASCII"));
        bb.put("FAT32   ".getBytes("US-ASCII"));
        vbr[510] = (byte) 0x55;
        vbr[511] = (byte) 0xAA;

        // FSInfo
        byte[] fsInfo = new byte[512];
        ByteBuffer fbb = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN);
        fbb.putInt(0x41615252);
        fbb.position(484);
        fbb.putInt(0x61417272);
        fbb.putInt((int) approxClusters - 1);
        fbb.putInt(3);
        fsInfo[510] = (byte) 0x55;
        fsInfo[511] = (byte) 0xAA;

        // FAT table sector 0
        byte[] fat = new byte[512];
        ByteBuffer fatBb = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN);
        fatBb.putInt(0x0FFFFFF8);
        fatBb.putInt(0x0FFFFFFF);
        fatBb.putInt(0x0FFFFFFF);

        device.writeSectors(startLba, vbr, 0, 512);
        device.writeSectors(startLba + 1, fsInfo, 0, 512);
        device.writeSectors(startLba + 6, vbr, 0, 512);
        device.writeSectors(startLba + 7, fsInfo, 0, 512);

        long fat1 = startLba + reserved;
        long fat2 = fat1 + sectorsPerFat;
        device.writeSectors(fat1, fat, 0, 512);
        device.writeSectors(fat2, fat, 0, 512);

        // Clear root directory
        byte[] rootZero = new byte[spc * 512];
        long rootLba = startLba + reserved + (numFats * sectorsPerFat);
        device.writeSectors(rootLba, rootZero, 0, rootZero.length);

        device.sync();
    }
}
