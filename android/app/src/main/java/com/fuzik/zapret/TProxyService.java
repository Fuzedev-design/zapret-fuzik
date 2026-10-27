package com.fuzik.zapret;

/**
 * Мост к hev-socks5-tunnel. Его JNI-код (hev-jni.c) регистрирует эти методы
 * именно на этом классе — имя задаётся флагами PKGNAME/CLSNAME в Application.mk.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();
}
