package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code timetracking} field: the same three quantities in both a human form ({@code "3d 4h"}) and
 * seconds.
 *
 * <p>Jira populates the seconds on read and accepts either form on write, but it does not accept both at
 * once for the same quantity - a payload carrying {@code originalEstimate} and
 * {@code originalEstimateSeconds} together is rejected. The factory methods here therefore build one form
 * or the other rather than filling in both.
 *
 * @param originalEstimate original estimate in Jira duration syntax
 * @param remainingEstimate remaining estimate in Jira duration syntax
 * @param timeSpent time logged in Jira duration syntax
 * @param originalEstimateSeconds original estimate in seconds
 * @param remainingEstimateSeconds remaining estimate in seconds
 * @param timeSpentSeconds time logged in seconds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimeTracking(String originalEstimate,
                           String remainingEstimate,
                           String timeSpent,
                           Long originalEstimateSeconds,
                           Long remainingEstimateSeconds,
                           Long timeSpentSeconds) {

    /** Sets only the original estimate, in Jira duration syntax such as {@code "2w 3d 4h"}. */
    public static TimeTracking originalEstimate(String estimate) {
        return new TimeTracking(estimate, null, null, null, null, null);
    }

    /** Sets only the remaining estimate, in Jira duration syntax. */
    public static TimeTracking remainingEstimate(String estimate) {
        return new TimeTracking(null, estimate, null, null, null, null);
    }

    /** Sets both estimates, which is what a create request normally wants. */
    public static TimeTracking estimates(String original, String remaining) {
        return new TimeTracking(original, remaining, null, null, null, null);
    }
}
