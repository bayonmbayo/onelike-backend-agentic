package com.onelike.app.controller;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The shared harness of every test class that writes: a full context with
 * MockMvc on an in-memory H2 schema Hibernate builds from the entities, and
 * drops with the context. A class using it declares nothing else - its own
 * database name is derived from its class name:
 *
 *     @IsolatedH2Test
 *     class SearchControllerFeedJobTest { ... }
 *
 * WHY the URL, driver and create-drop sit together in this one annotation and
 * never per class: without the datasource override, spring.datasource.url
 * falls back to onelike.database.url, i.e. the LIVE dev schema `onelike` on
 * the host MySQL (application.properties:148 and :33) - shared with the
 * communo backend - while ddl-auto would still say create-drop. Splitting the
 * three lines across classes is how one of them goes missing.
 *
 * How the URL gets its per-class database name, with no placeholder anywhere:
 *
 * - the URL below is a COMPLETE literal naming a sentinel database,
 *   isolated_h2_unnamed. It is never meant to be used.
 * - {@link IsolatedH2ContextCustomizerFactory} (registered in
 *   src/test/resources/META-INF/spring.factories) replaces it, at the highest
 *   property precedence, with jdbc:h2:mem:&lt;this class's name, non-alphanumerics
 *   as _&gt;;MODE=MySQL;NON_KEYWORDS=USER. An in-memory H2 name is a global
 *   handle within the JVM, so two classes sharing one would share rows and
 *   become order-dependent; and the customizer's equals/hashCode is the class
 *   name, so two classes with otherwise identical configuration also get two
 *   cached contexts rather than one.
 * - DB_CLOSE_DELAY is deliberately NOT set: the database dies with the last
 *   connection, so nothing survives the context.
 * - NON_KEYWORDS=USER is required: USER is reserved in H2 2.x and this
 *   feature's identity table is called exactly `user`.
 *
 * Two refusals, not warnings, because the symptom of either failure is remote:
 *
 * - {@link IsolatedH2Extension.RefuseUnlessIsolatedH2} runs before the
 *   context refreshes, i.e. before any bean exists or any connection opens
 *   (after the customizer, which Spring's ContextCustomizerAdapter applies
 *   ahead of declared initializers). It refuses a URL that is not
 *   jdbc:h2:mem:..., the sentinel URL itself (the customizer did not run, e.g.
 *   its spring.factories line is gone), and any driver but org.h2.Driver.
 * - {@link IsolatedH2Extension}, before each case, checks the live connection's
 *   URL is in-memory H2 and contains the class's simple name literally (a check
 *   independent of the name-deriving helper), installs this context's services
 *   into SearchController's and ArticleImageCache's statics, and refuses if
 *   that proxy pool is non-empty.
 *
 * The fixtures below are this harness's own invention, named so that nobody
 * can mistake them for a pasted secret, and are never asserted on. They exist
 * only so the context can satisfy the mandatory placeholders in
 * application.properties with no .env file present. jwt.secret is padded past
 * 64 bytes because ConsoleToken passes its raw bytes to Keys.hmacShaKeyFor for
 * HS512.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:isolated_h2_unnamed;MODE=MySQL;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Every scheduled job off: nothing here may spend proxy budget, fetch a
        // feed or purge a cache row.
        "onelike.database.searchnews=false",
        "onelike.database.proxies.enabled=false",
        "onelike.cleanup.enabled=false",
        "onelike.containers.refresh.enabled=false",
        // Placeholder fillers, of this harness's own choosing. Not credentials.
        "jwt.secret=test-fixture-jwt-secret-padded-past-the-sixty-four-byte-hs512-minimum-not-a-real-secret",
        "onelike.news.filter.apikey=test-fixture-news-filter-apikey-not-a-real-secret",
        "onelike.hibp.api-key=not-a-real-key",
        "onelike.webshare.api-key=not-a-real-key",
        "spring.mail.password=not-a-real-password",
        "file.upload-dir=target/test-uploads"
})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = IsolatedH2Extension.RefuseUnlessIsolatedH2.class)
@ExtendWith(IsolatedH2Extension.class)
public @interface IsolatedH2Test {
}
