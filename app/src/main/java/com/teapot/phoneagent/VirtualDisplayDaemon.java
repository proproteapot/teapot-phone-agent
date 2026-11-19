package com.teapot.phoneagent;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 由 Shizuku (uid=2000 shell) 通过 app_process 启动的离屏虚拟屏守护进程。
 * 遵循最小权限原则：仅负责创建与维护离屏 VirtualDisplay 并在收到 QUIT/EOF 时销毁，
 * 不包含任何特权文件写入能力。
 */
public final class VirtualDisplayDaemon {
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;

    private static volatile VirtualDisplay activeDisplay;
    private static volatile ImageReader activeReader;

    private static final class ShellFakeContext extends ContextWrapper {
        ShellFakeContext() {
            super(null);
        }

        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public String getAttributionTag() { return null; }
        @Override public Context getApplicationContext() { return this; }
        @Override public int getDeviceId() { return 0; }
        @Override public String getSystemServiceName(Class<?> serviceClass) {
            if (DisplayManager.class.equals(serviceClass)) return Context.DISPLAY_SERVICE;
            return null;
        }
        @Override public Object getSystemService(String name) { return null; }
        @Override public android.content.ContentResolver getContentResolver() { return null; }

        @Override
        public android.content.AttributionSource getAttributionSource() {
            if (Build.VERSION.SDK_INT >= 31) {
                return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                        .setPackageName("com.android.shell")
                        .build();
            }
            return super.getAttributionSource();
        }
    }

    public static void main(String[] args) {
        int width = args.length > 0 ? parseInt(args[0], 1080) : 1080;
        int height = args.length > 1 ? parseInt(args[1], 2400) : 2400;
        int dpi = args.length > 2 ? parseInt(args[2], 420) : 420;
        String name = args.length > 3 && !args[3].isEmpty() ? args[3] : "PhoneAgentVirtual";

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                try (Image img = r.acquireLatestImage()) {
                    // 消费最新帧，保持离屏 Surface 活跃
                } catch (Throwable ignored) { }
            }, new Handler(Looper.getMainLooper()));
            activeReader = reader;

            Context fakeCtx = new ShellFakeContext();
            DisplayManager dm = createDisplayManager(fakeCtx);
            int[] flagCandidates = new int[] {
                    VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                            | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                            | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                            | VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                            | VIRTUAL_DISPLAY_FLAG_TRUSTED
                            | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                            | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                            | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED,
                    VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                            | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                            | VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                            | VIRTUAL_DISPLAY_FLAG_TRUSTED,
                    VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | VIRTUAL_DISPLAY_FLAG_TRUSTED
            };

            VirtualDisplay vd = null;
            int usedFlags = 0;
            Throwable lastError = null;
            for (int flags : flagCandidates) {
                try {
                    vd = createVirtualDisplayCompat(fakeCtx, dm, name, width, height, dpi, reader.getSurface(), flags);
                    if (vd != null && vd.getDisplay() != null) {
                        usedFlags = flags;
                        break;
                    }
                } catch (Throwable t) {
                    lastError = t;
                }
            }

            if (vd == null || vd.getDisplay() == null) {
                System.out.println("ERROR:" + (lastError != null ? lastError.toString() : "createVirtualDisplay returned null"));
                System.out.flush();
                cleanup();
                System.exit(2);
                return;
            }

            activeDisplay = vd;
            int displayId = vd.getDisplay().getDisplayId();
            System.out.println("READY:" + displayId + ":" + usedFlags);
            System.out.flush();

            Thread cmdThread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if ("QUIT".equalsIgnoreCase(line.trim())) break;
                    }
                } catch (Throwable ignored) {
                } finally {
                    cleanup();
                    System.exit(0);
                }
            }, "vd-stdin");
            cmdThread.setDaemon(false);
            cmdThread.start();

            Looper.loop();
        } catch (Throwable t) {
            System.out.println("ERROR:" + t.getClass().getName() + ":" + t.getMessage());
            System.out.flush();
            cleanup();
            System.exit(1);
        }
    }

    private static DisplayManager createDisplayManager(Context fakeCtx) {
        try {
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            return ctor.newInstance(fakeCtx);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static VirtualDisplay createVirtualDisplayCompat(
            Context fakeCtx, DisplayManager dm, String name, int width, int height, int dpi, Surface surface, int flags
    ) throws Exception {
        try {
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgClass.getMethod("getInstance").invoke(null);
            Class<?> cfgBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = cfgBuilderClass.getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(name, width, height, dpi);
            cfgBuilderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
            cfgBuilderClass.getMethod("setFlags", int.class).invoke(builder, flags);
            Object config = cfgBuilderClass.getMethod("build").invoke(builder);

            for (Method m : dmgClass.getDeclaredMethods()) {
                if (!"createVirtualDisplay".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[2].getName().equals("android.hardware.display.VirtualDisplayConfig")) {
                    m.setAccessible(true);
                    Object res = m.invoke(dmg, fakeCtx, null, config, null, null);
                    if (res instanceof VirtualDisplay) return (VirtualDisplay) res;
                } else if (p.length == 6 && p[2].getName().equals("android.hardware.display.VirtualDisplayConfig")) {
                    m.setAccessible(true);
                    Object res = m.invoke(dmg, fakeCtx, null, config, null, null, null);
                    if (res instanceof VirtualDisplay) return (VirtualDisplay) res;
                }
            }
        } catch (Throwable ignored) { }

        if (dm != null) {
            return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
        }
        throw new IllegalStateException("无法通过 DisplayManagerGlobal 或 DisplayManager 创建离屏虚拟屏");
    }

    private static void cleanup() {
        try { if (activeDisplay != null) activeDisplay.release(); } catch (Throwable ignored) { }
        activeDisplay = null;
        try { if (activeReader != null) activeReader.close(); } catch (Throwable ignored) { }
        activeReader = null;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
