package android.widget;

public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static int shown;
    public static Toast makeText(android.content.Context c, CharSequence s, int d) { shown++; return new Toast(); }
    public void show() { }
}
