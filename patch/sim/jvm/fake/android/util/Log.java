package android.util;

public final class Log {
    public static boolean quiet = true;
    public static int e(String t, String m) { if (!quiet) System.err.println(t + " E " + m); return 0; }
    public static int e(String t, String m, Throwable x) { if (!quiet) System.err.println(t + " E " + m + " " + x); return 0; }
    public static int w(String t, String m) { if (!quiet) System.err.println(t + " W " + m); return 0; }
    public static int i(String t, String m) { return 0; }
    public static int d(String t, String m) { return 0; }
}
