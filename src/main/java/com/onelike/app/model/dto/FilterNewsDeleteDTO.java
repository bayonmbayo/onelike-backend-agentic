package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Body of POST /service/filternews/delete. A hard delete with no soft-delete
 * fallback: active:false is the reversible option and is a separate endpoint.
 *
 * No identity field - see FilterNewsDTO. @JsonIgnoreProperties is not
 * inherited, so it is repeated here.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterNewsDeleteDTO {
    private Long id;
}
