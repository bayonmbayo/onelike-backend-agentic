package com.onelike.app.repository;

/**
 * The identity of one live theme: its display title and its language, which
 * together are exactly the (socialmedia, language) key of the NewsBlock that
 * holds its articles.
 *
 * Interface projection, like UrlPinCount: the aliases in the JPQL SELECT
 * (AS name, AS language) are what Spring Data binds to these getters.
 */
public interface ThemeKey {
    String getName();
    String getLanguage();
}
