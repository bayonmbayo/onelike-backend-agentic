package com.onelike.app.model;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * One card on a signed-in user's Social News page, in that user's own order.
 *
 * Two kinds share the table so a single ordered list can mix them:
 * "PLATFORM" rows mirror a built-in name from util/SocialMediaPlatforms.ALL and
 * carry no content of their own (the client already has the platform feed),
 * "THEME" rows are the user's own titles ("AI", "Crypto"), whose articles live
 * in a NewsBlock keyed by the display title + language, shared by every user
 * who added the same theme. kind is a plain String, not an enum - there is no
 * enum precedent among this repo's entities.
 *
 * A user has no rows at all until their first write; reads fall back to the
 * 20 defaults, so an empty table means "never customised", not "empty page".
 *
 * language is part of a THEME's identity, which is why it is in the unique key:
 * a theme is only shown in the language it was created for, so the same user
 * may hold "Crypto"/English and "Crypto"/Deutsch as two independent cards. A
 * PLATFORM row is language-independent (the service only ever creates one per
 * name per user) and keeps the language it happened to be seeded with.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_unc_user_kind_namekey_language",
        columnNames = {"user_id", "kind", "namekey", "language"}))
@Data
public class UserNewsContainer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * The three columns below are in the unique key, so they carry explicit
     * lengths instead of Hibernate's default varchar(255): on utf8mb4 that
     * default is 1020 bytes per column, and three of them plus the FK sit right
     * at (or just over, depending on how the server accounts for the varchar
     * length prefix) InnoDB's 3072-byte index limit, which would fail table
     * creation with ER_TOO_LONG_KEY. These lengths put the key at well under
     * 800 bytes. "name" is NOT in the key and keeps the default.
     */
    @Column(length = 16)
    private String kind;

    /** Display title, exactly as typed (trimmed). This is the NewsBlock key for a THEME. */
    private String name;

    /**
     * Lowercased name, the case-insensitive identity used for lookups and
     * uniqueness. 128 rather than 40: a title is validated to 40 characters,
     * but lowercasing can lengthen a string (U+0130 maps to two characters), so
     * the column has to allow for that or a long non-ASCII title would be
     * truncated/rejected on insert.
     */
    @Column(length = 128)
    private String namekey;

    private Integer position;

    /** One of onelike.news.languages, e.g. "English" / "Deutsch" / "Francais". */
    @Column(length = 32)
    private String language;

    private Date createdat;
    private Date updatedat;
}
