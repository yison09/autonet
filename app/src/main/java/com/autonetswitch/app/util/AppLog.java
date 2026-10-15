package com.autonetswitch.app.util;

import com.autonetswitch.app.App;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AppLog {

    public interface Listener {
        void onLog(String line);
    }

    private static final long MAX_BYTES = 200 * 1024;
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    // M4：SimpleDateFormat 非线程安全，改为每次新建（append 频率很低，开销可忽略），
    // 并且文件写入/裁剪用同一把锁串行化，避免并发丢行或截断错位。
    private static final Object FILE_LOCK = new Object();

    private AppLog() {
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private static File file() {
        return new File(App.get().getFilesDir(), "autonet.log");
    }

    public static void append(String line) {
        String stamped;
        try {
            stamped = stamp() + "  " + line;
        } catch (Exception e) {
            stamped = "??  " + line;
        }
        try {
            synchronized (FILE_LOCK) {
                File f = file();
                if (f.exists() && f.length() > MAX_BYTES) {
                    trim(f, MAX_BYTES / 2);
                }
                java.io.FileWriter w = new java.io.FileWriter(f, true);
                w.write(stamped + "\n");
                w.close();
            }
        } catch (Exception ignored) {
        }
        for (Listener l : listeners) {
            try {
                l.onLog(stamped);
            } catch (Exception ignored) {
            }
        }
    }

    private static void trim(File f, long keepFromEnd) {
        try {
            synchronized (FILE_LOCK) {
                RandomAccessFile raf = new RandomAccessFile(f, "rw");
                long len = raf.length();
                long start = len - keepFromEnd;
                // 对齐到行首
                raf.seek(start);
                raf.readLine();
                byte[] rest = new byte[(int) (len - raf.getFilePointer())];
                raf.readFully(rest);
                raf.setLength(0);
                raf.write(rest);
                raf.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static List<String> tail(int n) {
        List<String> out = new ArrayList<>();
        try {
            synchronized (FILE_LOCK) {
            File f = file();
            if (!f.exists()) {
                return out;
            }
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 64 * 1024);
            raf.seek(start);
            if (start > 0) {
                raf.readLine(); // 丢弃半行
            }
            String line;
            while ((line = raf.readLine()) != null) {
                out.add(new String(line.getBytes("ISO-8859-1"), "UTF-8"));
            }
            raf.close();
            }
        } catch (Exception ignored) {
        }
        if (out.size() > n) {
            return new ArrayList<>(out.subList(out.size() - n, out.size()));
        }
        return out;
    }

    public static void clear() {
        try {
            synchronized (FILE_LOCK) {
                file().delete();
            }
        } catch (Exception ignored) {
        }
        append("日志已清空");
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }
}
