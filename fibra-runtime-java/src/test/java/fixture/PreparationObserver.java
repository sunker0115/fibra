package fixture;

import java.util.function.Consumer;

public final class PreparationObserver {
    public static Consumer<ClassLoader> callback;

    private PreparationObserver() {
    }
}
