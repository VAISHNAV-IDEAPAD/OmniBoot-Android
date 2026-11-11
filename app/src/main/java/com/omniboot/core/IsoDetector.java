package com.omniboot.core;

import android.content.Context;
import android.net.Uri;
import java.io.InputStream;

public class IsoDetector {
    public static class IsoInfo {
        public final String name;
        public final boolean isWindows;
        public final boolean isLinux;
        public final boolean hasWim;
        public final long wimSize;

        public IsoInfo(String n, boolean win, boolean lin, boolean wim, long ws) {
            this.name = n;
            this.isWindows = win;
            this.isLinux = lin;
            this.hasWim = wim;
            this.wimSize = ws;
        }
    }

    public static IsoInfo inspect(Context ctx, Uri uri, String filename) {
        boolean win = false;
        boolean lin = false;
        boolean wim = false;
        long wimSize = 0L;
        String name = "Operating System Image";

        String lower = filename.toLowerCase();
        if (lower.contains("win11") || lower.contains("windows 11")) {
            win = true; name = "Windows 11 (x64)"; wim = true; wimSize = 4900000000L;
        } else if (lower.contains("win10") || lower.contains("windows 10")) {
            win = true; name = "Windows 10 (x64)"; wim = true; wimSize = 4200000000L;
        } else if (lower.contains("win") || lower.contains("windows")) {
            win = true; name = "Windows OS"; wim = true; wimSize = 3500000000L;
        } else if (lower.contains("ubuntu")) {
            lin = true; name = "Ubuntu Linux";
        } else if (lower.contains("debian")) {
            lin = true; name = "Debian GNU/Linux";
        } else if (lower.contains("arch")) {
            lin = true; name = "Arch Linux";
        } else if (lower.contains("fedora")) {
            lin = true; name = "Fedora Linux";
        } else {
            lin = true; name = "Linux Hybrid Image";
        }

        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] sample = new byte[32768];
                int r = in.read(sample);
                if (r > 512) {
                    String s = new String(sample, "ISO-8859-1");
                    if (s.contains("ISOLINUX") || s.contains("GRUB")) {
                        lin = true; win = false;
                    } else if (s.contains("bootmgr") || s.contains("sources")) {
                        win = true; lin = false;
                    }
                }
            }
        } catch (Exception ignored) {}

        return new IsoInfo(name, win, lin, wim, wimSize);
    }
}
