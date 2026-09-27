package com.onelike.app.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PinDTO {
    private String url;
    private String title;
    private String source;
    private Boolean pinned;
    private String publishedat;
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;
}
