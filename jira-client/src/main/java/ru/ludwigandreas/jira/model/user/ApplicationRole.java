package ru.ludwigandreas.jira.model.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An application role - the licence bucket that decides whether an account consumes a Jira Software,
 * Jira Core or Jira Service Management seat.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationRole(String key,
                              String name,
                              List<String> groups,
                              List<String> defaultGroups,
                              Boolean selectedByDefault,
                              Boolean defined,
                              Integer numberOfSeats,
                              Integer remainingSeats,
                              Integer userCount,
                              String userCountDescription,
                              Boolean hasUnlimitedSeats,
                              Boolean platform) {
}
