package fixtures.deprecation;

/**
 * A deprecation that states everything the configuration requires.
 *
 * @deprecated use {@code fixtures.deprecation.Replacement} instead
 */
@Deprecated(since = "1.1.0", forRemoval = false)
public class CompliantDeprecation {

    /**
     * Returns nothing of interest.
     *
     * @return always zero
     * @deprecated use {@code Replacement.value()} instead
     */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public int value() {
        return 0;
    }
}
