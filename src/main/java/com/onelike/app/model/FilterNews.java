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
     * Who added the row and when, both filled by the Console on insert only and
     * left alone by a reactivate. Null for every row that predates the Console,
     * and that stays allowed: no constraint, no index, and deliberately no
     * unique key on name - the lookup has to be case-sensitive (String.equals
     * in FilterNewsConsoleService) and MySQL would compare the key
     * case-insensitively.
     */
    private String createdby;
    private Date createdat;
}
