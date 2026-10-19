package com.onelike.app.controller;

import com.onelike.app.repository.ArticleImageRepository;
import com.onelike.app.service.MyproxyService;
import com.onelike.app.service.NewsBlockService;
import com.onelike.app.service.NewsService;
import com.onelike.app.util.ArticleImageCache;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * The per-case half of {@link IsolatedH2Test}.
 *
 * Before each case:
 *
 * 1. REFUSES unless the live DataSource connection's URL is an in-memory H2
 *    database whose URL contains this class's simple name literally. That is
 *    deliberately NOT computed with IsolatedH2ContextCustomizerFactory's
 *    name-deriving helper, so the check compares two independent sources: a
 *    helper that returned a constant, or two classes sharing one cached
 *    context, fails here.
 * 2. Installs THIS context's MyproxyService, NewsService and NewsBlockService
 *    into SearchController's statics via installStaticDao, and this context's
 *    ArticleImageRepository into ArticleImageCache's static. Statics are per
 *    class loader, not per Spring context, and surefire runs every test class
 *    in one JVM, so without this whichever context was created LAST owns them -
 *    possibly a MySQL-backed onelike_test one.
 * 3. REFUSES if the proxy pool the create path will now consult is non-empty:
 *    with proxies, a create would open real sockets and could save a
 *    news_block row. Reading the count is a SELECT and writes nothing.
 *
 * After each case it puts back whatever the statics held before, so a class
 * that installs fakes on top (SearchControllerFeedJobTest) leaves nothing
 * behind either.
 */
class IsolatedH2Extension implements BeforeEachCallback, AfterEachCallback {

    static final String SENTINEL_DATABASE = "isolated_h2_unnamed";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(IsolatedH2Extension.class);

    private static final String[] STATICS = {"myproxyService", "newsService", "newsBlockService"};

    private static final String IMAGE_REPOSITORY = "imageRepository";

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        ApplicationContext app = SpringExtension.getApplicationContext(context);

        String url;
        try (Connection connection = app.getBean(DataSource.class).getConnection()) {
            url = connection.getMetaData().getURL();
        }
        if (url == null || !url.startsWith("jdbc:h2:mem:") || !url.contains(testClass.getSimpleName())) {
            throw new IllegalStateException("REFUSING to run " + testClass.getSimpleName()
                    + ": its DataSource is " + url + ", not an in-memory H2 database named after "
                    + testClass.getSimpleName());
        }

        Object[] previous = new Object[STATICS.length + 1];
        for (int i = 0; i < STATICS.length; i++) {
            previous[i] = ReflectionTestUtils.getField(SearchController.class, STATICS[i]);
        }
        previous[STATICS.length] = ReflectionTestUtils.getField(ArticleImageCache.class, IMAGE_REPOSITORY);
        context.getStore(NAMESPACE).put("previous", previous);

        SearchController.installStaticDao(app.getBean(MyproxyService.class), app.getBean(NewsService.class),
                app.getBean(NewsBlockService.class));
        ReflectionTestUtils.setField(ArticleImageCache.class, IMAGE_REPOSITORY,
                app.getBean(ArticleImageRepository.class));

        MyproxyService installed =
                (MyproxyService) ReflectionTestUtils.getField(SearchController.class, "myproxyService");
        long proxies = installed.getProxyNumber();
        if (proxies != 0L) {
            throw new IllegalStateException("REFUSING to run " + testClass.getSimpleName()
                    + ": the MyproxyService SearchController will use can see " + proxies
                    + " proxy/proxies, so a create here would spend real proxy budget and could write a"
                    + " news_block row.");
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Object[] previous = context.getStore(NAMESPACE).remove("previous", Object[].class);
        if (previous != null) {
            SearchController.installStaticDao((MyproxyService) previous[0], (NewsService) previous[1],
                    (NewsBlockService) previous[2]);
            ReflectionTestUtils.setField(ArticleImageCache.class, IMAGE_REPOSITORY, previous[STATICS.length]);
        }
    }

    /**
     * The second line of defence, behind IsolatedH2ContextCustomizerFactory.
     * Runs before the context refreshes: no bean exists yet and no connection
     * has been opened. It refuses
     *
     * - a spring.datasource.url that is not jdbc:h2:mem:... (e.g. the MySQL
     *   fallback, if the annotation's URL line were lost);
     * - the annotation's sentinel database, isolated_h2_unnamed, which is what
     *   is left when the per-class customizer did not run;
     * - any driver but org.h2.Driver.
     */
    static class RefuseUnlessIsolatedH2 implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            ConfigurableEnvironment environment = context.getEnvironment();
            String url = environment.getProperty("spring.datasource.url");
            if (url == null || !url.startsWith("jdbc:h2:mem:")) {
                throw new IllegalStateException("REFUSING to start: spring.datasource.url is not an in-memory H2"
                        + " database (jdbc:h2:mem:...) but " + url);
            }
            if (url.startsWith("jdbc:h2:mem:" + SENTINEL_DATABASE + ";") || url.equals("jdbc:h2:mem:" + SENTINEL_DATABASE)) {
                throw new IllegalStateException("REFUSING to start: spring.datasource.url is still the sentinel " + url
                        + " - IsolatedH2ContextCustomizerFactory did not give this class its own database"
                        + " (is it registered in META-INF/spring.factories?)");
            }
            if (!"org.h2.Driver".equals(environment.getProperty("spring.datasource.driver-class-name"))) {
                throw new IllegalStateException("REFUSING to start: spring.datasource.driver-class-name is not"
                        + " org.h2.Driver");
            }
        }
    }
}
