package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Body of POST /service/filternews.
 *
 * name is the publisher string exactly as Google News writes it after "- " at
 * the end of a headline ("The Hacker News"), not a domain and not a hostname:
 * SearchController.isGoodNews does a case-sensitive, end-anchored String match,
 * so "thehackernews.com" filters nothing at all.
 *
 * There is deliberately NO identity field here. Unlike ContainerDTO and PinDTO,
 * the Console takes its caller from the Authorization header only, because a
 * body-supplied role would be assertable by any client.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is mandatory and is not
 * inherited: FAIL_ON_UNKNOWN_PROPERTIES is on by default here with no
 * ObjectMapper customisation, so one extra client field would answer 400 with
 * a Jackson body instead of an ApiError.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterNewsDTO {
    private String name;
}
