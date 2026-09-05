package com.onelike.app.model;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Entity
@Data
public class NewsBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private Date updated;
    @Column(columnDefinition = "LONGTEXT")
    private String feed;
    private String language;
    private String socialmedia;
}
