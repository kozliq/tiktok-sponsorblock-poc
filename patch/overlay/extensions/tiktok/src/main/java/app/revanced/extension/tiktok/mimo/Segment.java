package app.revanced.extension.tiktok.mimo;

/** Отрезок рекламы в ролике: [startMs, endMs). */
public final class Segment {
    public final String uuid;
    public final long startMs;
    public final long endMs;
    public final String category;
    public final int votes;

    Segment(String uuid, long startMs, long endMs, String category, int votes) {
        this.uuid = uuid;
        this.startMs = startMs;
        this.endMs = endMs;
        this.category = category;
        this.votes = votes;
    }

    boolean contains(long pos) {
        return pos >= startMs && pos < endMs;
    }

    static String label(String category) {
        switch (category) {
            case "scam": return "скам";
            case "selfpromo": return "самопиар";
            case "intro": return "интро";
            default: return "рекламу";
        }
    }
}
