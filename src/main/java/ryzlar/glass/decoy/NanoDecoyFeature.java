package ryzlar.glass.decoy;

/**
 * The switch for the Nano Decoy. Off, it is not in the game at all: its item and entities are not registered, its recipe
 * is skipped (fabric:load_conditions), its renderers and tooltip are not set up, its mixins do nothing and its test
 * passes without running. Turn it off for a release it is not ready for.
 * <p>
 * Kept in its own class: reading it must not load {@link NanoDecoy}, whose fields register everything.
 */
public final class NanoDecoyFeature
{
    public static final boolean ENABLED = true;

    private NanoDecoyFeature() {}
}
