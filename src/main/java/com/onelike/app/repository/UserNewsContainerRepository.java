package com.onelike.app.repository;

import com.onelike.app.model.User;
import com.onelike.app.model.UserNewsContainer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Every query here is derived from its method name on purpose: Spring Data
 * parses derived names at bootstrap, so contextLoads catches a typo rather
 * than the first request doing so. findDistinctThemeKeys is the one exception
 * and has to be: a derived name cannot express a DISTINCT over two columns
 * projected into an interface. It is still JPQL, not a native query, so it is
 * validated at bootstrap just like the derived ones are.
 */
@Repository
public interface UserNewsContainerRepository extends JpaRepository<UserNewsContainer, Long> {

    List<UserNewsContainer> findByUserOrderByPositionAsc(User user);

    /**
     * Duplicate detection for a theme. The language is part of the lookup
     * because it is part of a theme's identity: the same title in another
     * language is a different card, not a duplicate.
     */
    UserNewsContainer findByUserAndKindAndNamekeyAndLanguage(User user, String kind, String namekey, String language);

    /**
     * Deliberately NOT language-scoped: a user's theme allowance is global, so
     * the count it is compared against counts every language together. The
     * allowance itself is per user (user.themequota, default 1, ceiling 10) and
     * lives in UserNewsContainerService.themeAllowance, not here.
     */
    long countByUserAndKind(User user, String kind);

    /**
     * Every distinct (title, language) a theme exists for, across all users -
     * one row per NewsBlock that the theme feature keeps alive, which is what
     * the refresh job iterates. Themes are cached per theme, not per user, so
     * ten users holding "Crypto" are one unit of work here.
     *
     * Known wrinkle, deliberately NOT worked around: both schemas collate
     * utf8mb4_0900_ai_ci, so this DISTINCT is case- and accent-insensitive
     * while NewsBlock lookups are not. Two different users holding "Crypto"
     * and "crypto" therefore own two news_block rows but collapse into one
     * row here, and whichever title MySQL does not return would never be
     * refreshed. No single user can get into that state - the per-user unique
     * key is on the lowercased namekey - and lowercasing here would be worse,
     * because it would refresh a key no NewsBlock is stored under.
     */
    @Query("SELECT DISTINCT c.name AS name, c.language AS language FROM UserNewsContainer c WHERE c.kind = :kind")
    List<ThemeKey> findDistinctThemeKeys(@Param("kind") String kind);
}
