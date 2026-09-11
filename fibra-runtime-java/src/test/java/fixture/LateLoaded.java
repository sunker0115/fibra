package fixture;

public final class LateLoaded {
    private LateLoaded() { }

    public static String value() {
        return "late";
    }
}
