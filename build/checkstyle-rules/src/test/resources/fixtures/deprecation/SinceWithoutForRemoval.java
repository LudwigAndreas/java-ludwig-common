package fixtures.deprecation;

/** A deprecation that names its version but not whether the element is going away. */
public class SinceWithoutForRemoval {

    /**
     * Returns nothing of interest.
     *
     * @return always zero
     * @deprecated use {@code Replacement.value()} instead
     */
    @Deprecated(since = "1.1.0")
    public int value() {
        return 0;
    }
}
