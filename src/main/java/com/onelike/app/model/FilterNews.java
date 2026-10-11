package com.onelike.app.model;

import jakarta.persistence.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Entity
@Data
public class FilterNews implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String name;
    @Column(columnDefinition = "boolean default true")
    private Boolean active;
    /**
     * Who added the row and when, written on insert only by whichever write
     * path inserted it and left alone by a reactivate. Null for every row that
     * predates both paths, and that stays allowed: no constraint, no index, and
     * deliberately no unique key on name - the lookup has to be case-sensitive
     * (String.equals in FilterNewsConsoleService) and MySQL would compare the
     * key case-insensitively.
     *
     * createdby is NOT always a person, and not always a Console token subject.
     * There are two write paths: the ADMIN/MODERATOR Console writes the
     * caller email from the token sub, and the API-key-gated agent endpoint
     * (FilterNewsAgentController) writes the literal "agent". Read it as an
     * opaque label, never as a user identifier.
     */
    private String createdby;
    private Date createdat;
}
