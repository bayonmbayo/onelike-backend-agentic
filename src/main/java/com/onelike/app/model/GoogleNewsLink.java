package com.onelike.app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * Cache of Google News article id -> publisher URL.
 *
 * The mapping never changes once resolved, so a row is written once and never
 * rewritten - but it is not permanent: CleanupService purges rows no pin
 * depends on every 72 hours, and a purged id is simply decoded again on next
 * use. Keyed on a SHA-256 of the article id rather than the id itself:
 * ids run past 700 characters, which overflows the 3072-byte unique index limit
 * on MySQL/MariaDB with utf8mb4.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "google_news_link",
        indexes = @Index(name = "idx_gnl_article_hash", columnList = "article_hash", unique = true))
public class GoogleNewsLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_hash", nullable = false, unique = true, length = 64)
    private String articleHash;

    @Column(name = "publisher_url", nullable = false, length = 2048)
    private String publisherUrl;

    @Column(name = "created")
    private Date created;

    public GoogleNewsLink(String articleHash, String publisherUrl) {
        this.articleHash = articleHash;
        this.publisherUrl = publisherUrl;
        this.created = new Date();
    }
}