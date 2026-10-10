package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Body of POST /service/container.
 *
 * Careful: name is the CALLER's surname (same identity block as PinDTO), the
 * container's own title is title.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is mandatory, not decoration:
 * FAIL_ON_UNKNOWN_PROPERTIES is on by default here and there is no ObjectMapper
 * customisation, so one extra client field would answer 400 with a Jackson
 * body instead of an ApiError.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerDTO {
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;
    private String title;
    private String language;
}
