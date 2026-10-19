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
    /**
     * The preview entries the card showed when this feed was stored, written
     * in the same save as feed by SearchController.articlesOf. Nullable with
     * no default: a row stored before this column existed holds NULL, which
     * every reader serves as [] (SearchController.savedLinksPreviewOf).
     */
    @Column(columnDefinition = "LONGTEXT")
    private String linkspreview;
    private String language;
    private String socialmedia;
}
