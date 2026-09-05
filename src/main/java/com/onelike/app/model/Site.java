package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;

@Entity
@Data
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(columnDefinition = "LONGTEXT")
    private String name;
    @Column(columnDefinition = "LONGTEXT")
    private String domain;
    @Column(columnDefinition = "LONGTEXT")
    private String description;
    private String logo;

    @ManyToOne
    @JoinColumn(name = "breach_id")
    private Breach breach;
}
