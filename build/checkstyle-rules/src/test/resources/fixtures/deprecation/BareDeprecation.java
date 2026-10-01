package fixtures.deprecation;

/** A deprecation with no members and no Javadoc tag. */
public class BareDeprecation {

    /** Returns nothing of interest. */
    @Deprecated
    public int value() {
        return 0;
    }
}
