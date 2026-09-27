package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Data
public class InfoNews {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(columnDefinition = "VARCHAR(1000)")
    private String url;
    @Column(columnDefinition = "VARCHAR(1000)")
    private String title;
    private String source;
    private Date publishedat;
    private Date savedat;
    @Column(columnDefinition = "VARCHAR(1000)")
    private String image;
}
