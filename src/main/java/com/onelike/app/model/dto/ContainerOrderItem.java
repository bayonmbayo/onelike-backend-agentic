package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * One entry of the ordered list sent to POST /service/containers/order.
 * Matched against stored rows by kind + lowercased name, never by id, so the
 * client can reorder the default platforms it has never written before.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContainerOrderItem {
    private String kind;
    private String name;
}
