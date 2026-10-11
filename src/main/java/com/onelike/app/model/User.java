package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;

    /**
     * How many custom Social News themes this user may hold, across all
     * languages. An admin edits it by hand with raw SQL; nothing in this
     * application ever writes it.
     *
     * NULL means "use the configured default"
     * (onelike.containers.default-themes-per-user, 1), which is NOT the same as
     * 0 - zero legitimately means "no custom cards at all".
     *
     * Nullable, with no Java initialiser and no columnDefinition, all three
     * deliberately:
     *
     * - a DB-side DEFAULT would be worse than absent. Hibernate writes every
     *   mapped column on INSERT, so `columnDefinition = "integer default 1"`
     *   would never fire for a row THIS repo inserts (it sends an explicit
     *   NULL) and would fire only for rows the communo backend inserts. The
     *   result is a column where 1 and NULL mean the same thing by accident -
     *   exactly the filter_news.active failure shape.
     * - this table is SHARED with the communo backend in dev (same `onelike`
     *   schema, see CLAUDE.md), and that backend does not map this column at
     *   all, so every row it inserts reads NULL here. That is the intended
     *   "default allowance", not a defect to migrate away.
     * - the field name is lowercase, like every other field on this entity, so
     *   the column is literally `themequota` with no camelCase surprise for an
     *   admin writing SQL.
     *
     * The value is NOT trusted as stored: UserNewsContainerService.themeAllowance
     * clamps it to [0, onelike.containers.max-themes-per-user] on every read,
     * because it arrives from a hand-typed UPDATE. Enforcement is at create
     * only - lowering an allowance never deletes, hides or renumbers a theme
     * the user already holds.
     */
    private Integer themequota;

    private Date createdat;
    private Date updatedat;
}
