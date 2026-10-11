package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Body of POST /service/filternews/active: the reversible switch, as opposed to
 * /service/filternews/delete. Both exist on purpose.
 *
 * active is a boxed Boolean so that an omitted field is distinguishable from
 * false and answers filternews.active.missing rather than silently switching
 * the row off.
 *
 * No identity field - see FilterNewsDTO. @JsonIgnoreProperties is not
 * inherited, so it is repeated here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterNewsActiveDTO {
    private Long id;
    private Boolean active;
}
