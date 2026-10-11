package com.onelike.app.service;

import com.onelike.app.controller.SearchController;
import com.onelike.app.exception.ContainerException;
import com.onelike.app.model.NewsBlock;
import com.onelike.app.model.User;
import com.onelike.app.model.UserNewsContainer;
import com.onelike.app.model.dto.ContainerDTO;
import com.onelike.app.model.dto.ContainerDeleteDTO;
import com.onelike.app.model.dto.ContainerOrderDTO;
import com.onelike.app.model.dto.ContainerOrderItem;
import com.onelike.app.repository.UserNewsContainerRepository;
import com.onelike.app.repository.UserRepository;
import com.onelike.app.util.InfoMint;
import com.onelike.app.util.SocialMediaPlatforms;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Social News cards of one signed-in user: the 20 built-in platforms plus
 * the user's own themes ("AI", "Crypto"), in the user's own order.
 *
 * Three deliberate properties of this service:
 *
 * 1. Reads never write. A user who has never customised anything has no rows at
 *    all and still gets the 20 defaults back (seeded:false), and a read also
 *    reconciles: platforms added to SocialMediaPlatforms.ALL after a user was
 *    seeded are appended as views, stored platform rows whose name has since
 *    left ALL are skipped but kept. Rows are created only on the user's first
 *    write (create / reorder).
 *
 * 2. Theme content is cached per theme, not per user. A theme's articles live
 *    in NewsBlock keyed by (display title, language), which is exactly the key
 *    SearchController.articlesOf uses, so the second user who adds "Crypto"
 *    reuses the first user's fetch and costs no proxy budget at all.
 *
 * 3. A theme belongs to the language it was created for and is invisible in
 *    every other language: a "Crypto" created in English does not appear in the
 *    German Social News, and the same user may create a separate German
 *    "Crypto" (language is part of a theme's identity, see isRendered and the
 *    unique key on UserNewsContainer). The 20 platforms, by contrast, exist in
 *    every language and are shared by all of them - including their order.
 *    The theme allowance is global, not per language: five English themes plus
 *    five German ones are ten, however they are spread.
 *
 * The allowance itself is PER USER, not a fixed ten: the nullable
 * user.themequota column, which an admin edits by hand with raw SQL. NULL
 * means "use onelike.containers.default-themes-per-user" (1), and
 * onelike.containers.max-themes-per-user (10) is a hard ceiling no per-user
 * value may exceed. No role is read anywhere on this path. themeAllowance is
 * the single definition of it; nothing re-derives it per call site.
 *
 * Enforcement is at CREATE only. Lowering a user's allowance below what they
 * already hold deletes nothing, hides nothing and renumbers nothing: they keep
 * their cards, read used > allowed, and simply cannot add another until they
 * are back under it. used > allowed is a normal state, not an error.
 */
@Service
@Slf4j
public class UserNewsContainerService {

    public static final String KIND_PLATFORM = "PLATFORM";
    public static final String KIND_THEME = "THEME";
    public static final String DEFAULT_LANGUAGE = "English";

    public static final int TITLE_MAX_LENGTH = 40;

    @Autowired
    UserNewsContainerRepository containerRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    NewsBlockService newsBlockService;

    @Value("${onelike.containers.max-themes-per-user:10}")
    private int maxThemesPerUser;

    /** The allowance of a user whose themequota is NULL. */
    @Value("${onelike.containers.default-themes-per-user:1}")
    private int defaultThemesPerUser;

    /** A NewsBlock younger than this is reused instead of triggering a live fetch. */
    @Value("${onelike.containers.block-max-age-ms:3600000}")
    private long blockMaxAgeMs;

    /**
     * Same list and same default as SearchController: note the values are
     * "Deutsch" / "Francais", not "German" / "French".
     */
    @Value("${onelike.news.languages:"
            + "English,Deutsch,Spanish,Francais,Greek,Hindi,Indonesian,Italian,"
            + "Japanese,Dutch,Portuguese,Russian,Turkish,Arabic,Chinese}")
    private String[] newsLanguages;

    // ------------------------------------------------------------------
    // Allowance
    // ------------------------------------------------------------------

    /**
     * A misconfigured default is silently swallowed by the clamp in
     * themeAllowance - a default of 25 against a ceiling of 10 behaves exactly
     * like a default of 10, so nothing downstream can ever reveal it. Warn
     * once at startup rather than inventing a behaviour (throwing would take
     * the whole application down for a cosmetic misconfiguration, and raising
     * the ceiling would silently defeat it).
     */
    @PostConstruct
    void warnAboutTheAllowanceConfiguration() {
        if (defaultThemesPerUser > maxThemesPerUser) {
            log.warn("I have onelike.containers.default-themes-per-user=" + defaultThemesPerUser
                    + " above onelike.containers.max-themes-per-user=" + maxThemesPerUser
                    + ", so every user without a themequota is clamped to " + maxThemesPerUser);
        }
    }

    /**
     * How many themes this user is allowed to hold, across all languages. The
     * SINGLE definition of that: no call site may re-derive it, or the two
     * answers drift apart exactly as isRendered was written to prevent.
     *
     * Two deliberate properties:
     *
     * - a NULL themequota (and a caller with no User row at all, which is what
     *   a missing, blank or unknown uuid resolves to) answers
     *   defaultThemesPerUser. NULL is not 0: a stored 0 legitimately means "no
     *   custom cards at all".
     * - the stored value is clamped on every READ, not on write, because
     *   nothing in this application writes it - it arrives from an admin's
     *   hand-typed UPDATE. A typed 25 therefore behaves as the ceiling and a
     *   negative as 0, with no DB constraint to maintain and no way for a bad
     *   row to grant more than onelike.containers.max-themes-per-user.
     */
    int themeAllowance(User user) {
        int quota = (user == null || user.getThemequota() == null)
                ? defaultThemesPerUser
                : user.getThemequota();
        return Math.max(0, Math.min(maxThemesPerUser, quota));
    }

    /**
     * The themes object the three reading/mutating endpoints carry, as a
     * sibling of their other top-level keys.
     *
     * Both values are int, and "used" is narrowed at every call site rather
     * than here taking a long: countByUserAndKind answers a long, and this
     * signature is what forces the cast to be visible at the call site instead
     * of being a widening nobody notices. The contract pins both as JSON
     * numbers, never null and never strings, so there is exactly one primitive
     * type in play on this path.
     *
     * Deliberately NOT on /containers/order: a reorder cannot change the theme
     * count.
     */
    JSONObject themesJson(User user, int used) {
        JSONObject themes = new JSONObject();
        themes.put("used", used);
        themes.put("allowed", themeAllowance(user));
        return themes;
    }

    /**
     * The user's THEME rows counted from a list that is already in hand, for
     * the read and delete paths - one pass over a list we loaded anyway rather
     * than a second COUNT query.
     *
     * Counts EVERY language, not the one being rendered: the allowance is
     * global, so the count it is compared against has to be too.
     */
    private int themeCountOf(List<UserNewsContainer> containers) {
        int used = 0;
        for (UserNewsContainer container : containers) {
            if (KIND_THEME.equals(container.getKind())) {
                used++;
            }
        }
        return used;
    }

    // ------------------------------------------------------------------
    // A - list
    // ------------------------------------------------------------------

    /**
     * The caller's cards for ONE language: every platform, plus only the themes
     * created for that language. A missing, blank or unknown uuid is not an
     * error: it answers the 20 defaults with seeded:false, exactly like a known
     * user who has never written anything.
     *
     * seeded stays language-independent on purpose - it answers "has this user
     * ever customised anything", and the platform rows it is really about are
     * shared by all languages. A user with only a German theme therefore reads
     * seeded:true with the 20 untouched defaults in English.
     *
     * themes is always present, including for an unresolved caller, who reads
     * used:0 with the default allowance - there is no row to read a quota from.
     * used is counted from the list already loaded, over EVERY language, so
     * this still costs exactly the one query it always did.
     */
    public JSONObject myContainers(String uuid, String language) throws Exception {
        String resolvedLanguage = normalizeLanguage(language);

        User user = null;
        List<UserNewsContainer> stored = new ArrayList<>();
        if (uuid != null && !uuid.trim().isEmpty()) {
            user = userRepository.findUserByUuid(uuid.trim());
            if (user != null) {
                stored = containerRepository.findByUserOrderByPositionAsc(user);
            }
        }

        JSONObject result = new JSONObject();
        result.put("seeded", !stored.isEmpty());
        result.put("themes", themesJson(user, themeCountOf(stored)));
        result.put("containers", containersJson(stored, resolvedLanguage));
        return result;
    }

    // ------------------------------------------------------------------
    // B - create
    // ------------------------------------------------------------------

    /**
     * Adds a theme. Idempotent: an existing theme with the same case-insensitive
     * title answers created:false and that container rather than an error.
     *
     * themes is on BOTH answers and is post-insert on the created:true one, so
     * a client that disables its Add button from used >= allowed is correct
     * immediately with no re-fetch.
     *
     * @param title the already trimmed and validated title
     */
    public JSONObject createContainer(ContainerDTO containerDTO, String title) throws Exception {
        String language = normalizeLanguage(containerDTO.getLanguage());
        User user = upsertUser(containerDTO);
        String namekey = title.toLowerCase();

        // Same title in another language is a different card, so the language is
        // part of the lookup: it is what lets a user who made "Crypto" in
        // English make a German "Crypto" too, instead of getting created:false
        // and a row they cannot see in German news.
        UserNewsContainer existing = containerRepository
                .findByUserAndKindAndNamekeyAndLanguage(user, KIND_THEME, namekey, language);
        if (existing != null) {
            JSONObject result = new JSONObject();
            result.put("created", false);
            result.put("container", themeJson(existing, wirePosition(user, existing, language), language));
            result.put("themes", themesJson(user, (int) containerRepository.countByUserAndKind(user, KIND_THEME)));
            return result;
        }

        // The user's own allowance (user.themequota, default 1, ceiling 10) and
        // global, not per language: however they spread their themes over
        // languages, they all count against the one number.
        if (containerRepository.countByUserAndKind(user, KIND_THEME) >= themeAllowance(user)) {
            throw new ContainerException("container.limit.reached");
        }

        List<UserNewsContainer> stored = containerRepository.findByUserOrderByPositionAsc(user);
        int position;
        if (stored.isEmpty()) {
            seedDefaults(user, language);
            position = SocialMediaPlatforms.ALL.size();
        } else {
            position = nextPosition(stored);
        }

        UserNewsContainer container = new UserNewsContainer();
        container.setUser(user);
        container.setKind(KIND_THEME);
        container.setName(title);
        container.setNamekey(namekey);
        container.setPosition(position);
        container.setLanguage(language);
        container.setCreatedat(new Date());
        containerRepository.save(container);

        loadThemeContent(title, language);

        JSONObject result = new JSONObject();
        result.put("created", true);
        result.put("container", themeJson(container, wirePosition(user, container, language), language));
        // Recounted, deliberately NOT "the pre-insert count + 1". The +1 is
        // correct today and is an off-by-one waiting for whoever next edits
        // this block; one COUNT is noise against a create that can legitimately
        // spend 60 seconds on a live fetch.
        result.put("themes", themesJson(user, (int) containerRepository.countByUserAndKind(user, KIND_THEME)));
        return result;
    }

    /**
     * The themes a fetch is in flight for right now, keyed title|language.
     *
     * news_block has no unique constraint on (socialmedia, language) and
     * articlesOf does find-then-save, so two concurrent fetches of the same
     * theme can both find nothing and both insert. Two rows for one key make
     * NewsBlockRepository.findBySocialMediaAndLanguage throw
     * NonUniqueResultException on EVERY later read of that card, permanently -
     * the card is dead until somebody deletes a row by hand. That window is
     * reachable: a theme created at the same moment a refresh tick picks it up
     * has no block yet, so it sorts first as the most stale candidate.
     *
     * Single JVM only. It is not a distributed lock and is not meant to be: the
     * cost of losing the race across instances is one wasted fetch plus the
     * duplicate above, which only a DB constraint could actually rule out.
     */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Makes sure a NewsBlock exists for this theme, fetching live only when the
     * cached block is missing or older than onelike.containers.block-max-age-ms.
     *
     * The single definition of "fetch a theme", shared by the create path and
     * ThemeRefreshService so the two can never disagree about what fresh means
     * or about how articlesOf is called.
     *
     * Failures are swallowed on purpose: on the create path the container has
     * already been created, and an empty news array is a legitimate answer when
     * every proxy is down.
     *
     * The third argument of articlesOf MUST be a throwaway JSONArray - the list
     * it is given is the global all-news feed (News property=2) that /searchnews
     * serves, and a user's private theme has no business leaking into it.
     *
     * @return true if a live fetch was actually attempted, false if the cached
     *         block was still fresh, another thread was already on it, or the
     *         fetch failed
     */
    boolean loadThemeContent(String title, String language) {
        if (!IN_FLIGHT.add(title + "|" + language)) {
            log.debug("I am skipping the theme " + title + " " + language + ", a fetch is already in flight");
            return false;
        }
        try {
            NewsBlock block = newsBlockService.findBySocialMediaAndLanguage(title, language);
            if (isFresh(block)) {
                log.debug("I am reusing the cached news block for the theme " + title + " " + language);
                return false;
            }

            log.info("I am fetching live news for the theme " + title + " " + language
                    + " (this spends proxy budget)");
            SearchController.articlesOf(language, title, new JSONArray());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("I could not fetch news for the theme " + title + " " + language);
            return false;
        } finally {
            IN_FLIGHT.remove(title + "|" + language);
        }
    }

    boolean isFresh(NewsBlock block) {
        return block != null
                && block.getFeed() != null
                && block.getUpdated() != null
                && (new Date().getTime() - block.getUpdated().getTime()) <= blockMaxAgeMs;
    }

    // ------------------------------------------------------------------
    // C - delete
    // ------------------------------------------------------------------

    /**
     * Deletes one of the caller's own themes and renumbers what is left to
     * 0..n-1. Platform rows are never deletable.
     *
     * @return the themes object for the response, post-delete. It is
     *         load-bearing rather than symmetry: the frontend's reducer patches
     *         its container list locally on a successful delete and
     *         deliberately does not re-fetch, so without this the client's
     *         "used" would stay stale-high and its Add button would stay
     *         disabled at exactly the moment the user had made room. Counted
     *         from "remaining", which this method already loads for densify, so
     *         it costs no extra query.
     */
    @Transactional
    public JSONObject deleteContainer(ContainerDeleteDTO containerDeleteDTO) throws Exception {
        User user = resolveCaller(containerDeleteDTO.getUuid(), containerDeleteDTO.getFirstname(),
                containerDeleteDTO.getName(), containerDeleteDTO.getDisplayname(), containerDeleteDTO.getEmail());

        UserNewsContainer container = containerRepository.findById(containerDeleteDTO.getId()).orElse(null);
        if (container == null
                || !KIND_THEME.equals(container.getKind())
                || container.getUser() == null
                || container.getUser().getId() != user.getId()) {
            throw new ContainerException("container.notfound");
        }

        containerRepository.delete(container);

        List<UserNewsContainer> remaining = containerRepository.findByUserOrderByPositionAsc(user);
        densify(remaining);
        containerRepository.saveAll(remaining);

        return themesJson(user, themeCountOf(remaining));
    }

    // ------------------------------------------------------------------
    // D - reorder
    // ------------------------------------------------------------------

    /**
     * Applies the client's complete ordered list. Never destructive: stored rows
     * the client left out keep their relative order and are appended after the
     * submitted ones, and submitted entries that match nothing are ignored.
     *
     * One transaction for all rows - unlike CleanupService, where each step is
     * its own transaction on purpose, a half-applied order is worse than none.
     *
     * The order arrives from ONE language's page, so it can only contain that
     * language's themes; the other languages' themes are among the omitted rows
     * and are therefore preserved, in their previous relative order, after the
     * submitted ones. There is a single position column shared by all
     * languages, so that is as far as it goes: a reorder done in German pushes
     * the English themes behind the platforms in the English view (their order
     * among themselves is kept, and nothing is ever lost). Platform order is
     * shared by every language by design.
     */
    @Transactional
    public JSONArray reorder(ContainerOrderDTO containerOrderDTO) throws Exception {
        User user = resolveCaller(containerOrderDTO.getUuid(), containerOrderDTO.getFirstname(),
                containerOrderDTO.getName(), containerOrderDTO.getDisplayname(), containerOrderDTO.getEmail());
        String language = normalizeLanguage(containerOrderDTO.getLanguage());

        List<UserNewsContainer> stored = containerRepository.findByUserOrderByPositionAsc(user);
        if (stored.isEmpty()) {
            seedDefaults(user, language);
            stored = containerRepository.findByUserOrderByPositionAsc(user);
        }

        Map<String, UserNewsContainer> byKey = new HashMap<>();
        for (UserNewsContainer container : stored) {
            byKey.put(key(container.getKind(), container.getNamekey(), themeLanguage(container)), container);
        }

        List<UserNewsContainer> ordered = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        int ignored = 0;

        for (ContainerOrderItem item : containerOrderDTO.getContainers()) {
            if (item == null || item.getKind() == null || item.getName() == null
                    || item.getName().trim().isEmpty()) {
                ignored++;
                continue;
            }

            String kind = item.getKind().trim().toUpperCase();
            String submittedName = item.getName().trim();
            // The submitted language is what a THEME is matched on, so a German
            // reorder can never grab, renumber or hide the English theme of the
            // same name.
            UserNewsContainer container = byKey.get(key(kind, submittedName.toLowerCase(), language));

            if (container == null && KIND_PLATFORM.equals(kind) && SocialMediaPlatforms.isPlatform(submittedName)) {
                // A platform that joined SocialMediaPlatforms.ALL after this user
                // was seeded: a read shows it, so a reorder has to be able to
                // place it. This is a write path already, so give it a row.
                container = platformRow(user, canonicalPlatformName(submittedName), ordered.size(), language);
                containerRepository.save(container);
                byKey.put(key(KIND_PLATFORM, container.getNamekey(), themeLanguage(container)), container);
            }

            if (container == null || taken.contains(container.getId())) {
                ignored++;
                continue;
            }

            ordered.add(container);
            taken.add(container.getId());
        }

        for (UserNewsContainer container : stored) {
            if (!taken.contains(container.getId())) {
                ordered.add(container);
            }
        }

        if (ignored > 0) {
            log.warn("I am ignoring " + ignored + " unknown container(s) of the submitted order for "
                    + containerOrderDTO.getEmail());
        }

        densify(ordered);
        containerRepository.saveAll(ordered);

        return containersJson(ordered, language);
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /**
     * Create path: upsert by email, mirroring PinService.pin - the caller is
     * whoever the body says they are, because there is no server-side session.
     */
    private User upsertUser(ContainerDTO containerDTO) {
        return upsertUser(containerDTO.getUuid(), containerDTO.getFirstname(), containerDTO.getName(),
                containerDTO.getDisplayname(), containerDTO.getEmail());
    }

    private User upsertUser(String uuid, String firstname, String name, String displayname, String email) {
        User user = userRepository.findUserByEmail(email);
        if (user == null) {
            user = new User();
            user.setUuid(uuid);
            user.setFirstname(firstname);
            user.setName(name);
            user.setDisplayname(displayname);
            user.setEmail(email);
            user.setCreatedat(new Date());
            userRepository.save(user);
        } else {
            user.setUuid(uuid);
            user.setFirstname(firstname);
            user.setName(name);
            user.setDisplayname(displayname);
            user.setUpdatedat(new Date());
            userRepository.save(user);
        }
        return user;
    }

    /**
     * Mutating paths that touch existing rows (delete, reorder): the email and
     * the uuid must resolve to the SAME user, so a body can never act on
     * somebody else's rows.
     *
     * Deliberately NOT merged with upsertUser: one shared "resolve the caller"
     * helper would quietly make all three endpoints upsert and the guard would
     * be gone.
     *
     * If neither lookup resolves, this is a genuine first write (a brand new
     * user whose very first action is a reorder), so it upserts like the create
     * path - nothing can be stolen when there is nothing stored yet.
     */
    private User resolveCaller(String uuid, String firstname, String name, String displayname, String email)
            throws ContainerException {
        User byEmail = userRepository.findUserByEmail(email);
        User byUuid = userRepository.findUserByUuid(uuid);

        if (byEmail == null && byUuid == null) {
            return upsertUser(uuid, firstname, name, displayname, email);
        }

        if (byEmail == null || byUuid == null || byEmail.getId() != byUuid.getId()) {
            throw new ContainerException("container.identity.mismatch");
        }

        return byEmail;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private void seedDefaults(User user, String language) {
        log.info("I am seeding the " + SocialMediaPlatforms.ALL.size() + " default containers for " + user.getEmail());
        List<UserNewsContainer> rows = new ArrayList<>();
        for (int i = 0; i < SocialMediaPlatforms.ALL.size(); i++) {
            rows.add(platformRow(user, SocialMediaPlatforms.ALL.get(i), i, language));
        }
        containerRepository.saveAll(rows);
    }

    private UserNewsContainer platformRow(User user, String name, int position, String language) {
        UserNewsContainer container = new UserNewsContainer();
        container.setUser(user);
        container.setKind(KIND_PLATFORM);
        container.setName(name);
        container.setNamekey(name.toLowerCase());
        container.setPosition(position);
        container.setLanguage(language);
        container.setCreatedat(new Date());
        return container;
    }

    private void densify(List<UserNewsContainer> containers) {
        for (int i = 0; i < containers.size(); i++) {
            UserNewsContainer container = containers.get(i);
            if (container.getPosition() == null || container.getPosition() != i) {
                container.setPosition(i);
                container.setUpdatedat(new Date());
            }
        }
    }

    private int nextPosition(List<UserNewsContainer> containers) {
        int max = -1;
        for (UserNewsContainer container : containers) {
            if (container.getPosition() != null && container.getPosition() > max) {
                max = container.getPosition();
            }
        }
        return max + 1;
    }

    private int positionOf(UserNewsContainer container) {
        return container.getPosition() == null ? 0 : container.getPosition();
    }

    /**
     * The index this row has in the array the listing endpoints return for this
     * language, so a single-container answer agrees with A and D. Uses the same
     * isRendered predicate, so a row that is not rendered does not consume an
     * index either. Falls back to the stored position if the row is somehow not
     * in the user's list.
     */
    private int wirePosition(User user, UserNewsContainer container, String language) {
        int index = 0;
        for (UserNewsContainer stored : containerRepository.findByUserOrderByPositionAsc(user)) {
            if (stored.getId() == container.getId()) {
                return index;
            }
            if (!isRendered(stored, language)) {
                continue;
            }
            index++;
        }
        return positionOf(container);
    }

    /**
     * Whether a stored row is part of what a read of this language shows - the
     * single definition of that, shared by containersJson and wirePosition so
     * the two can never drift apart.
     *
     * A THEME belongs to the language it was created for and is invisible in
     * all the others. A PLATFORM exists in every language, but is skipped (not
     * deleted) once its name has left SocialMediaPlatforms.ALL.
     */
    private boolean isRendered(UserNewsContainer container, String language) {
        if (KIND_THEME.equals(container.getKind())) {
            return themeLanguage(container).equalsIgnoreCase(language);
        }
        return SocialMediaPlatforms.isPlatform(container.getName());
    }

    /**
     * The language a theme belongs to. A row written before language was stored
     * (or with a blank one) counts as English rather than as "every language",
     * so it stays visible in exactly one Social News.
     */
    private String themeLanguage(UserNewsContainer container) {
        return languageOf(container, DEFAULT_LANGUAGE);
    }

    /**
     * Every date that goes on the wire has to pass through here.
     *
     * json-smart special-cases java.util.Date EXACTLY and writes it with
     * toString() ("Sat Sep 19 09:27:15 CEST 2026"), which is the form
     * /service/news already emits for a platform card. A subclass does not hit
     * that branch: Hibernate hands back java.sql.Timestamp, which falls through
     * to bean reflection and serialises as {"nanos":63000000}. So the same
     * container answered a string on the create that built it (in-memory Date)
     * and an object on every later read (Timestamp from the DB).
     *
     * Copying the millis into a plain java.util.Date removes both the wrong
     * form and the instability. Note the copy must stay: Timestamp.getTime()
     * alone is not enough, the declared type is what json-smart dispatches on.
     */
    private Date wireDate(Date date) {
        return date == null ? null : new Date(date.getTime());
    }

    private String languageOf(UserNewsContainer container, String fallback) {
        return container.getLanguage() == null || container.getLanguage().trim().isEmpty()
                ? fallback
                : container.getLanguage();
    }

    /**
     * Match key for the reorder. The language is part of it for a THEME, whose
     * identity includes the language, and deliberately NOT for a PLATFORM: the
     * 20 platform rows are seeded once with whatever language the user happened
     * to be reading at the time and are shared by every language, so keying
     * them by language would make a German reorder match none of them.
     */
    private String key(String kind, String namekey, String language) {
        if (KIND_THEME.equals(kind)) {
            return kind + "|" + namekey + "|" + language.toLowerCase();
        }
        return kind + "|" + namekey;
    }

    private String canonicalPlatformName(String name) {
        for (String platform : SocialMediaPlatforms.ALL) {
            if (platform.equalsIgnoreCase(name)) {
                return platform;
            }
        }
        return name;
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * Renders the stored rows that belong to this language (see isRendered),
     * then reconciles without writing anything: platforms of ALL with no row are
     * appended at the end in ALL order. An empty input therefore renders the 20
     * defaults.
     *
     * Themes of other languages are simply not rendered - they keep their row
     * and their position, they are just not part of this language's page.
     *
     * The position on the wire is the index in this array, so it is always dense
     * and gap-free even for the appended rows that have no stored position.
     */
    private JSONArray containersJson(List<UserNewsContainer> containers, String language) throws Exception {
        JSONArray result = new JSONArray();
        Set<String> renderedPlatforms = new HashSet<>();

        for (UserNewsContainer container : containers) {
            if (!isRendered(container, language)) {
                continue;
            }
            if (KIND_THEME.equals(container.getKind())) {
                result.add(themeJson(container, result.size(), themeLanguage(container)));
            } else {
                renderedPlatforms.add(container.getName().toLowerCase());
                result.add(platformJson(canonicalPlatformName(container.getName()), result.size()));
            }
        }

        for (String platform : SocialMediaPlatforms.ALL) {
            if (!renderedPlatforms.contains(platform.toLowerCase())) {
                result.add(platformJson(platform, result.size()));
            }
        }

        return result;
    }

    /**
     * A built-in card: the client already has its feed from /service/news, so
     * only the identity and the place in the order travel.
     */
    private JSONObject platformJson(String name, int position) {
        JSONObject container = new JSONObject();
        container.put("kind", KIND_PLATFORM);
        container.put("name", name);
        container.put("position", position);
        return container;
    }

    /**
     * A user card, with its content. description and website are deliberately
     * empty/null: a theme is not a social network, so the UrlWebsite /
     * Description defaults ("https://www.google.com" and "Describe me") would be
     * actively wrong here.
     *
     * linkspreview is recomputed on every read from the article_image cache and
     * is NOT stored (see previewOf). A card with no block, or a block with a
     * null feed, still answers [].
     */
    private JSONObject themeJson(UserNewsContainer container, int position, String language) throws Exception {
        JSONObject result = new JSONObject();
        result.put("kind", KIND_THEME);
        result.put("name", container.getName());
        result.put("position", position);
        result.put("id", container.getId());

        NewsBlock block = newsBlockService.findBySocialMediaAndLanguage(container.getName(), language);
        JSONArray linkspreview = new JSONArray();
        if (block == null || block.getFeed() == null) {
            result.put("news", new JSONArray());
            result.put("last_update", wireDate(container.getUpdatedat() != null
                    ? container.getUpdatedat()
                    : container.getCreatedat()));
        } else {
            // Parsed ONCE: the very object that goes out as "news" is the one
            // the previews are derived from, so the two fields can only ever
            // describe the same snapshot of the feed. A second read or a second
            // parse of the block would let a preview entry point at an article
            // this response does not contain.
            //
            // Guarded per card: /service/mycontainers renders every one of the
            // caller's themes in one response, so an unparseable news_block.feed
            // (a truncated write, a hand-edited row) would otherwise throw out
            // of containersJson and 500 the WHOLE page - every platform card
            // included. Degrading the single bad card to the empty-block answer
            // is the same state a theme already legitimately has before its
            // first fetch lands, so the client needs no new branch for it.
            Object feed = null;
            try {
                feed = new JSONParser(JSONParser.MODE_PERMISSIVE).parse(block.getFeed());
            } catch (Exception e) {
                e.printStackTrace();
                log.warn("I could not parse the news block of the theme " + container.getName() + " " + language
                        + ", so this card answers no news");
            }

            if (feed == null) {
                result.put("news", new JSONArray());
                result.put("last_update", wireDate(block.getUpdated()));
            } else {
                result.put("news", feed);
                result.put("last_update", wireDate(block.getUpdated()));
                linkspreview = previewOf(feed, language, container.getName());
            }
        }

        result.put("description", "");
        result.put("website", null);
        result.put("linkspreview", linkspreview);
        return result;
    }

    /**
     * The preview entries for a theme card, recomputed from the already parsed
     * feed on every read.
     *
     * This costs no proxy budget and issues no page fetch: it goes through
     * SearchController.giveMeArrayOfLinksFromCache, whose null proxy makes
     * ArticleImageCache return right after its one batched cache query. The
     * rows it reads are there because articlesOf already scanned these very
     * pages when it built the block - loadThemeContent discards the JSONObject
     * articlesOf returns, so the only thing that was ever lost was this small
     * projection, which is why recovering it here is free.
     *
     * Deliberately NOT stored on NewsBlock: article_image purges negatives, so
     * a stored snapshot would rot against the cache while the feed it belongs
     * to is still current. Recompute-on-read always agrees with the cache as it
     * is now.
     *
     * The first N cut and the 20/11 constants mirror articlesOf exactly, down
     * to the same language.equalsIgnoreCase("English") test, because the feed
     * is the same sorted array articlesOf stored: taking its head reproduces
     * the same candidates in the same order. Keeping the cap (rather than
     * passing the whole feed, which the cache would answer just as cheaply)
     * also guards a feed whose later items were never scanned, or were scanned
     * under a different constant.
     *
     * Never throws: a feed that does not parse as an array of objects costs the
     * card its previews, never its news or its last_update.
     */
    private JSONArray previewOf(Object feed, String language, String title) {
        try {
            if (!(feed instanceof JSONArray)) {
                return new JSONArray();
            }
            JSONArray news = (JSONArray) feed;

            int numPreviewImages = language.equalsIgnoreCase("English")
                    ? InfoMint.NUMBER_OF_PREVIEW_IMAGES_FOR_ENGLISH
                    : InfoMint.NUMBER_OF_PREVIEW_IMAGES;

            JSONArray candidates = new JSONArray();
            for (int i = 0; i < news.size() && i < numPreviewImages; i++) {
                candidates.add(news.get(i));
            }

            return SearchController.giveMeArrayOfLinksFromCache(candidates, language, title);
        } catch (Exception e) {
            e.printStackTrace();
            log.debug("I could not build the preview images for the theme " + title + " " + language);
            return new JSONArray();
        }
    }

    // ------------------------------------------------------------------
    // Language
    // ------------------------------------------------------------------

    /**
     * An unrecognised language falls back to English instead of erroring - the
     * language only picks which NewsBlock is read, and a bad value should not
     * cost the user their card.
     */
    public String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        if (newsLanguages != null) {
            for (String known : newsLanguages) {
                if (known != null && known.trim().equalsIgnoreCase(language.trim())) {
                    return known.trim();
                }
            }
        }
        return DEFAULT_LANGUAGE;
    }
}
