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
 * Cache of publisher page URL -> preview image URL.
 *
 * An article's image does not change once published, so a page is scanned once
 * and the result reused - but the row is not kept forever: CleanupService
 * purges rows no pin depends on every 72 hours, and a purged page is simply
 * rescanned on next use. Keyed on a SHA-256 of the page URL rather than the
 * URL itself: URLs run past the 3072-byte unique index limit on MySQL/MariaDB
 * with utf8mb4.
 *
 * A null imageUrl is a deliberate negative result ("scanned, nothing usable or
 * reachable found"), not a missing row -- it stops the page from being sent
 * through the proxy again on every cycle.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "article_image",
        indexes = @Index(name = "idx_ai_url_hash", columnList = "url_hash", unique = true))
public class ArticleImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 hex of the publisher page URL. */
    @Column(name = "url_hash", nullable = false, unique = true, length = 64)
    private String urlHash;

    /** null = scanned, no usable/reachable image. */
    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    /** Last time the page went through the proxy. */
    @Column(name = "checked", nullable = false)
    private Date checked;

    public ArticleImage(String urlHash, String imageUrl) {
        this.urlHash = urlHash;
        this.imageUrl = imageUrl;
        this.checked = new Date();
    }
}
