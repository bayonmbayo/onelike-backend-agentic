package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Data
public class Pin {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private Boolean pinned;
    private Date pinnedat;

    @ManyToOne
    @JoinColumn(name = "info_news_id")
    private InfoNews infoNews;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}
