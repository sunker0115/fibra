import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class HangingJvmFixture {
    private HangingJvmFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        Thread.currentThread().setName("fibra-short-timeout-fixture");
        var child = new ProcessBuilder("sh", "-c",
            "trap '' TERM; while :; do sleep 1; done").start();
        Files.writeString(Path.of(arguments[0]),
            ProcessHandle.current().pid() + "\n" + child.pid() + "\n");
        Thread.sleep(Duration.ofMinutes(5));
    }
}
