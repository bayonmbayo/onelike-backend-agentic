package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Body of POST /service/container/delete. id is a THEME container id owned by
 * the caller; PLATFORM rows are never deletable.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerDeleteDTO {
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;
    private Long id;
}
