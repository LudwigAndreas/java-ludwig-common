package ru.ludwigandreas.jira.request;

/**
 * What a worklog write should do to the issue's remaining estimate.
 *
 * <p>Jira expresses this as an {@code adjustEstimate} query parameter plus, for two of the four modes, a
 * second parameter carrying a duration. Modelling it as one object keeps the two from being set
 * inconsistently - {@code adjustEstimate=new} without a {@code newEstimate} is a 400, and
 * {@code adjustEstimate=auto} with one is silently ignored.
 *
 * <p>{@link #auto()} is Jira's default and subtracts the logged time from the remaining estimate.
 */
public final class EstimateAdjustment {

    private final String mode;
    private final String parameterName;
    private final String value;

    private EstimateAdjustment(String mode, String parameterName, String value) {
        this.mode = mode;
        this.parameterName = parameterName;
        this.value = value;
    }

    /** Subtract the logged time from the remaining estimate. Jira's default. */
    public static EstimateAdjustment auto() {
        return new EstimateAdjustment("auto", null, null);
    }

    /** Leave the remaining estimate exactly as it is. */
    public static EstimateAdjustment leave() {
        return new EstimateAdjustment("leave", null, null);
    }

    /** Replace the remaining estimate with the given duration, in Jira syntax. */
    public static EstimateAdjustment setTo(String newEstimate) {
        return new EstimateAdjustment("new", "newEstimate", newEstimate);
    }

    /** Reduce the remaining estimate by the given duration, in Jira syntax. */
    public static EstimateAdjustment reduceBy(String amount) {
        return new EstimateAdjustment("manual", "reduceBy", amount);
    }

    /** Increase the remaining estimate by the given duration; used when deleting or updating a worklog. */
    public static EstimateAdjustment increaseBy(String amount) {
        return new EstimateAdjustment("manual", "increaseBy", amount);
    }

    /** The {@code adjustEstimate} value. */
    public String mode() {
        return mode;
    }

    /** The name of the companion query parameter, or {@code null} when this mode needs none. */
    public String parameterName() {
        return parameterName;
    }

    /** The companion parameter's value, or {@code null}. */
    public String value() {
        return value;
    }
}
