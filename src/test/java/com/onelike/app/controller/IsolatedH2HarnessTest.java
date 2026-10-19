package com.onelike.app.controller;

import com.onelike.app.repository.ArticleImageRepository;
import com.onelike.app.service.MyproxyService;
import com.onelike.app.service.NewsBlockService;
import com.onelike.app.service.NewsService;
import com.onelike.app.util.ArticleImageCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IsolatedH2Test} harness's own mechanisms, with no context and no
 * database: the per-class customizer factory, the initializer that refuses
 * before refresh, and the extension's afterEach restore of the statics. The H2 test classes exercise both end to end; these
 * pin the cases those classes cannot reach without breaking themselves.
 */
class IsolatedH2HarnessTest {

    /** Not annotated: must be left alone, like every MySQL-profile test class. */
    static class Unannotated {
    }

    @IsolatedH2Test
    static class AnnotatedOne {
    }

    @IsolatedH2Test
    static class AnnotatedTwo {
    }

    private static final IsolatedH2ContextCustomizerFactory FACTORY = new IsolatedH2ContextCustomizerFactory();

    private static ContextCustomizer customizerFor(Class<?> testClass) {
        return FACTORY.createContextCustomizer(testClass, Collections.emptyList());
    }

    /** The annotation's own sentinel URL, read from the annotation rather than restated here. */
    private static String sentinelUrl() {
        for (String property : IsolatedH2Test.class.getAnnotation(SpringBootTest.class).properties()) {
            if (property.startsWith("spring.datasource.url=")) {
                return property.substring("spring.datasource.url=".length());
            }
        }
        throw new AssertionError("@IsolatedH2Test declares no spring.datasource.url");
    }

    private static void initialize(String url, String driver) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", url)
                .withProperty("spring.datasource.driver-class-name", driver);
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        new IsolatedH2Extension.RefuseUnlessIsolatedH2().initialize(context);
    }

    @Test
    void theFactoryIgnoresAClassWithoutTheAnnotation() {
        assertNull(customizerFor(Unannotated.class));
    }

    @Test
    void twoAnnotatedClassesGetDistinctUnequalCustomizers() {
        ContextCustomizer one = customizerFor(AnnotatedOne.class);
        ContextCustomizer two = customizerFor(AnnotatedTwo.class);
        assertNotNull(one);
        assertNotNull(two);
        assertNotEquals(one, two, "unequal customizers are what keep two classes out of one cached context");
        assertNotEquals(IsolatedH2ContextCustomizerFactory.urlOf(AnnotatedOne.class.getName()),
                IsolatedH2ContextCustomizerFactory.urlOf(AnnotatedTwo.class.getName()));
        // Sanity of the control: the same class twice IS equal, so the cache can still reuse a context.
        assertTrue(one.equals(customizerFor(AnnotatedOne.class)) && one.hashCode() == customizerFor(AnnotatedOne.class).hashCode());
    }

    @Test
    void theInitializerRefusesTheSentinelUrl() {
        String sentinel = sentinelUrl();
        assertTrue(sentinel.startsWith("jdbc:h2:mem:" + IsolatedH2Extension.SENTINEL_DATABASE + ";"),
                "the annotation's URL should name the sentinel database: " + sentinel);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> initialize(sentinel, "org.h2.Driver"));
        assertTrue(refused.getMessage().contains("sentinel"), refused.getMessage());
    }

    @Test
    void theInitializerRefusesANonH2Url() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> initialize("jdbc:mysql://127.0.0.1:1/refused", "org.h2.Driver"));
        assertTrue(refused.getMessage().contains("not an in-memory H2"), refused.getMessage());
    }

    @Test
    void theInitializerRefusesANonH2Driver() {
        String perClass = IsolatedH2ContextCustomizerFactory.urlOf(AnnotatedOne.class.getName());
        initialize(perClass, "org.h2.Driver"); // control: a per-class URL with the H2 driver is accepted
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> initialize(perClass, "com.mysql.cj.jdbc.Driver"));
        assertTrue(refused.getMessage().contains("driver-class-name"), refused.getMessage());
    }

    /** An interface instance whose every method fails: a value that must only be stored and compared. */
    private static <T> T inert(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            throw new AssertionError(type.getSimpleName() + "." + method.getName() + " must not be called");
        }));
    }

    /** An ExtensionContext answering only getStore(), over a plain map; nothing else may be asked of it. */
    private static ExtensionContext contextOver(Map<Object, Object> values) {
        ExtensionContext.Store store = (ExtensionContext.Store) Proxy.newProxyInstance(
                ExtensionContext.Store.class.getClassLoader(), new Class<?>[]{ExtensionContext.Store.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("remove")) {
                        return values.remove(args[0]);
                    }
                    if (method.getName().equals("get") && args.length >= 1) {
                        return values.get(args[0]);
                    }
                    throw new AssertionError("Store." + method.getName() + " is not expected from afterEach");
                });
        return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
                new Class<?>[]{ExtensionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getStore")) {
                        return store;
                    }
                    throw new AssertionError("ExtensionContext." + method.getName() + " is not expected from afterEach");
                });
    }

    /**
     * afterEach puts back exactly what beforeEach recorded - the three
     * SearchController statics and ArticleImageCache's repository - so a class
     * that installs fakes (SearchControllerFeedJobTest) leaves nothing behind
     * for the next class in the same JVM. No context: the statics are cleared,
     * afterEach runs over a store holding four distinct sentinels, and each
     * static must then be that very sentinel. Whatever the statics held before
     * this case is restored in the finally.
     */
    @Test
    void afterEachPutsBackWhatTheStaticsHeldBefore() {
        Object originalProxies = ReflectionTestUtils.getField(SearchController.class, "myproxyService");
        Object originalNews = ReflectionTestUtils.getField(SearchController.class, "newsService");
        Object originalBlocks = ReflectionTestUtils.getField(SearchController.class, "newsBlockService");
        Object originalImages = ReflectionTestUtils.getField(ArticleImageCache.class, "imageRepository");

        MyproxyService proxies = new MyproxyService();
        NewsService news = new NewsService();
        NewsBlockService blocks = new NewsBlockService();
        ArticleImageRepository images = inert(ArticleImageRepository.class);
        Map<Object, Object> values = new HashMap<>();
        // The key IsolatedH2Extension.beforeEach stores under.
        values.put("previous", new Object[]{proxies, news, blocks, images});
        try {
            SearchController.installStaticDao(null, null, null);
            ReflectionTestUtils.setField(ArticleImageCache.class, "imageRepository", null);
            assertNull(ReflectionTestUtils.getField(SearchController.class, "myproxyService"), "control: cleared");

            new IsolatedH2Extension().afterEach(contextOver(values));

            assertSame(proxies, ReflectionTestUtils.getField(SearchController.class, "myproxyService"));
            assertSame(news, ReflectionTestUtils.getField(SearchController.class, "newsService"));
            assertSame(blocks, ReflectionTestUtils.getField(SearchController.class, "newsBlockService"));
            assertSame(images, ReflectionTestUtils.getField(ArticleImageCache.class, "imageRepository"));
            assertTrue(values.isEmpty(), "the recorded statics should have been taken out of the store");
        } finally {
            SearchController.installStaticDao((MyproxyService) originalProxies, (NewsService) originalNews,
                    (NewsBlockService) originalBlocks);
            ReflectionTestUtils.setField(ArticleImageCache.class, "imageRepository", originalImages);
        }
    }
}
