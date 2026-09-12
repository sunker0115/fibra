package fixture;

public final class PrivateGreetingProvider implements PrivateGreeting {
    @Override
    public String message() { return "private greeting"; }
}
