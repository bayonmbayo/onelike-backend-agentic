package com.onelike.app.repository;

import com.onelike.app.model.User;
import com.onelike.app.model.UserNewsContainer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Every query here is derived from its method name on purpose: Spring Data
 * parses derived names at bootstrap, so contextLoads catches a typo rather
 * than the first request doing so.
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

    /** Deliberately NOT language-scoped: the theme cap is global per user. */
    long countByUserAndKind(User user, String kind);
}
