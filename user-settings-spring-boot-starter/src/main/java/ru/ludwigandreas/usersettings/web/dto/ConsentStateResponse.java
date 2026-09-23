package ru.ludwigandreas.usersettings.web.dto;

import java.util.List;

/** Where a subject's consents stand: the most recent decision for each. */
public record ConsentStateResponse(List<ConsentResponse> consents) {
}
