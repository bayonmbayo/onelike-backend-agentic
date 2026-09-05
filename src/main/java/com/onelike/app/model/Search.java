package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;
import java.util.Date;
import java.util.List;

@Entity
@Data
public class Search {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(columnDefinition = "LONGTEXT")
    private String searchtext;
    private Date searched;
    private boolean successful;
    private int times;
    private String ipaddress;
    @ElementCollection
    private List<String> links;
    private Integer results;
}
