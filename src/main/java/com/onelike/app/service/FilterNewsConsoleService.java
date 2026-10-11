package com.onelike.app.service;

import com.onelike.app.exception.FilterNewsException;
import com.onelike.app.model.FilterNews;
import com.onelike.app.repository.FilterNewsRepository;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * The publisher filter's write and read logic: list every row, add one, switch
 * one on or off, delete one.
 *
 * It has TWO callers, with different gates, and the name of this class names
 * only the first of them:
 *
 * - FilterNewsController, the ADMIN/MODERATOR Console, token-gated by
 *   ConsoleToken. It reaches all four methods, and createFilter's createdby is
 *   the caller's email taken from the token's sub.
 * - FilterNewsAgentController, an automated agent, gated by a shared API key
 *   (AgentApiKey). It is add-only: it reaches createFilter and nothing else,
 *   and passes the literal "agent" as createdby.
 *
 * So createdby is NOT always a token subject. It is one of: an email (Console),
 * the literal "agent", or null for a row predating both paths. Anything reading
 * it must treat it as an opaque label, not as a user identifier.
 *
 * Bodies are hand-built with net.minidev.json and returned as a String by the
 * controller, like UserNewsContainerController and the rest of the news
 * endpoints.
 *
 * Three properties of this service are load-bearing rather than incidental:
 *
 * 1. Reads go through filterNewsRepository.findAll(), NOT through
 *    NewsService.getAllFilterNews(). That one delegates to
 *    getActiveFilterNews(true), so it would hand the Console a list with every
 *    inactive row missing - i.e. the Console would not show the rows it exists
 *    to manage. Note it carries @Cacheable("filter_news") but that annotation
 *    never applies on a read: getAllFilterNews() calls allFilterNews()
 *    unqualified, and a self-invocation bypasses the caching interceptor under
 *    proxy-mode AOP. The cache entry does exist, because CacheLoader calls
 *    allFilterNews() externally, but no read path consults it. So staleness is
 *    not the reason to avoid that method - the active-only filter is.
 *
 * 2. The name lookup compares in Java with String.equals, never with a derived
 *    findByName. Matching at fetch time is case-sensitive (isGoodNews does an
 *    end-anchored String.endsWith), but MySQL's default collation is not, so
 *    findByName("the hacker news") would return the "The Hacker News" row and
 *    reactivating it would make the admin believe a lowercase filter is live
 *    when it filters nothing. Loading the whole table is affordable: it is
 *    small, and the fetch path already reads all of it on every run.
 *
 * 3. Nothing here evicts the filter_news cache. That is deliberate: the only
 *    available lever, CacheService.evictCache(), is all-or-nothing and would
 *    also dump all 15 language news caches and quotes, forcing a re-read burst
 *    on the hot path. No lag is introduced by that choice: the fetch path does
 *    not read through the cache at all, because getAllFilterNews() calls
 *    allFilterNews() unqualified and a self-invocation bypasses the caching
 *    interceptor under proxy-mode AOP, so every run queries the database. The
 *    real delay is the per-language job ladder - English performJob runs on a
 *    5-minute fixedRate - not the 10-minute CacheLoader tick, which populates
 *    an entry no read path consults. Earlier text here claimed an
 *    up-to-ten-minute cache lag; that was wrong in both cause and magnitude.
 */
@Slf4j
@Service
public class FilterNewsConsoleService {

    /**
     * Longer than UserNewsContainerService.TITLE_MAX_LENGTH (40), which is too
     * short for real publisher strings - "The Chronicle of Higher Education" is
     * 33 and longer ones exist - and well inside the VARCHAR(255) Hibernate
     * gives an unconstrained String, so no column change is needed.
     */
    public static final int NAME_MAX_LENGTH = 120;

    private static final String CODE_NAME_INVALID = "filternews.name.invalid";

    @Autowired
    FilterNewsRepository filterNewsRepository;

    /**
     * The one place a publisher name is trimmed and checked, for both write
     * paths: the token-gated Console and the API-key-gated agent endpoint.
     *
     * It is static and takes the raw string so it can be called before any
     * repository work and tested without a context. The three rules, and the
     * order they run in, are exactly what the Console controller enforced
     * inline before this method existed - a trimmed name must be non-empty, at
     * most NAME_MAX_LENGTH characters and free of ISO control characters -
     * because the Console's wire behaviour is pinned by tests and by
     * CONSOLE-CONTRACT.md and must not shift.
     *
     * Checking after the trim is the point: " " is empty, and a name padded
     * past 120 by whitespace is accepted at its trimmed length. A control
     * character is rejected rather than stripped, because matching at fetch
     * time is an exact end-anchored String.endsWith - a silently stripped
     * character would produce a stored name that looks right in the Console and
     * filters nothing.
     *
     * @return the trimmed name, which is what goes into the row
     */
    public static String normaliseName(String raw) throws FilterNewsException {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH || hasControlCharacter(name)) {
            throw new FilterNewsException(CODE_NAME_INVALID);
        }
        return name;
    }

    private static boolean hasControlCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every row, active and inactive, newest first.
     *
     * Ordered by id descending rather than by createdat: createdat is null for
     * every row that predates the Console, so sorting on it would scatter the
     * pre-existing filters unpredictably. The identity id is monotonic, so it
     * is the reliable "newest" here.
     */
    public JSONObject allFilters() {
        List<FilterNews> filterNewsList = new ArrayList<>(filterNewsRepository.findAll());
        filterNewsList.sort(Comparator.comparingLong(FilterNews::getId).reversed());

        JSONArray filters = new JSONArray();
        for (FilterNews filterNews : filterNewsList) {
            filters.add(wireFilter(filterNews));
        }

        JSONObject result = new JSONObject();
        result.put("filters", filters);
        return result;
    }

    /**
     * Adds the filter, or reactivates the row that already carries this exact
     * name, or does nothing at all if that row is already active.
     *
     * An existing-and-active name is an idempotent 200 with added:false and
     * reactivated:false, not an error, so a double-submitting popup is safe.
     * createdby/createdat are written on insert only; a reactivate leaves the
     * original pair alone.
     */
    @Transactional
    public JSONObject createFilter(String name, String createdby) {
        FilterNews existing = findByExactName(name);

        JSONObject result = new JSONObject();

        if (existing != null) {
            // Boolean.TRUE.equals, not == true: active is a nullable column and
            // a legacy row may hold NULL, which counts as off and must be
            // reactivated rather than reported as already on.
            boolean reactivated = !Boolean.TRUE.equals(existing.getActive());
            if (reactivated) {
                existing.setActive(true);
                existing = filterNewsRepository.save(existing);
            }
            result.put("added", false);
            result.put("reactivated", reactivated);
            result.put("filter", wireFilter(existing));
            return result;
        }

        FilterNews filterNews = new FilterNews();
        filterNews.setName(name);
        // Explicit, and the single most important line in this class.
        // FilterNewsRepository.getActiveFilterNews is "WHERE fn.active =
        // :active" and SQL NULL = TRUE is NULL, not true, so a row whose active
        // is NULL is invisible to the read path forever. The
        // @Column(columnDefinition = "boolean default true") default only
        // applies when the column is omitted from the INSERT, which Hibernate
        // never does - it writes every mapped column, so an unset Boolean is
        // written as NULL. The symptom would be "the filter does nothing",
        // indistinguishable from a mistyped name.
        filterNews.setActive(true);
        filterNews.setCreatedby(createdby);
        filterNews.setCreatedat(new Date());

        filterNews = filterNewsRepository.save(filterNews);

        result.put("added", true);
        result.put("reactivated", false);
        result.put("filter", wireFilter(filterNews));
        return result;
    }

    @Transactional
    public JSONObject updateActive(Long id, Boolean active) throws FilterNewsException {
        FilterNews filterNews = filterNewsRepository.findById(id)
                .orElseThrow(() -> new FilterNewsException("filternews.notfound"));

        filterNews.setActive(active);
        filterNews = filterNewsRepository.save(filterNews);

        JSONObject result = new JSONObject();
        result.put("updated", true);
        result.put("filter", wireFilter(filterNews));
        return result;
    }

    /**
     * Hard delete. The reversible option is updateActive, and both exist on
     * purpose. ADMIN and MODERATOR may both do this.
     */
    @Transactional
    public void deleteFilter(Long id) throws FilterNewsException {
        if (!filterNewsRepository.existsById(id)) {
            throw new FilterNewsException("filternews.notfound");
        }
        filterNewsRepository.deleteById(id);
    }

    /**
     * Case-SENSITIVE name lookup, done in Java on purpose. See property 2 in
     * the class comment: a derived findByName would be resolved by MySQL's
     * case-insensitive collation and could return - and reactivate - a row
     * whose name differs from the one the admin typed.
     */
    private FilterNews findByExactName(String name) {
        for (FilterNews filterNews : filterNewsRepository.findAll()) {
            if (name.equals(filterNews.getName())) {
                return filterNews;
            }
        }
        return null;
    }

    private JSONObject wireFilter(FilterNews filterNews) {
        JSONObject filter = new JSONObject();
        filter.put("id", filterNews.getId());
        filter.put("name", filterNews.getName());
        // Never null on the wire even though the column is nullable: the client
        // renders a switch off this and has nothing sensible to do with null.
        filter.put("active", Boolean.TRUE.equals(filterNews.getActive()));
        filter.put("createdby", filterNews.getCreatedby());
        filter.put("createdat", wireDate(filterNews.getCreatedat()));
        return filter;
    }

    /**
     * Every date that goes on the wire has to pass through here - the same
     * choke point, and for the same reason, as UserNewsContainerService.wireDate.
     *
     * json-smart special-cases java.util.Date EXACTLY and writes it with
     * toString() ("Sat Oct 11 17:06:51 CEST 2026"). A subclass does not hit
     * that branch: Hibernate hands back java.sql.Timestamp, which falls through
     * to bean reflection and serialises as {"nanos":63000000}. Without this
     * copy, createdat would be a string in the create response that inserted
     * the row and an object on every later read.
     *
     * The copy must stay: Timestamp.getTime() alone is not enough, the declared
     * type is what json-smart dispatches on.
     */
    private Date wireDate(Date date) {
        return date == null ? null : new Date(date.getTime());
    }
}
