package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.*;
import java.util.Date;

import java.io.Serializable;

@Entity
@Data
public class News implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private Date updated;
    @Column(columnDefinition = "LONGTEXT")
    private String feed;
    @Column(columnDefinition = "LONGTEXT")
    private String allFeedWithoutCategory;
    private String language;
    private Integer property;
}
