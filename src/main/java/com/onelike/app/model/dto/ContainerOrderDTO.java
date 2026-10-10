package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Body of POST /service/containers/order. containers is the COMPLETE ordered
 * list as the client shows it; anything the client leaves out is appended, not
 * deleted.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerOrderDTO {
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;
    private String language;
    private List<ContainerOrderItem> containers;
}
