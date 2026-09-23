package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code progress} and {@code aggregateprogress} fields: work logged against work estimated, in seconds.
 *
 * @param progress seconds logged
 * @param total seconds estimated
 * @param percent completion percentage Jira computed, present only on some endpoints
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Progress(Long progress, Long total, Integer percent) {
}
