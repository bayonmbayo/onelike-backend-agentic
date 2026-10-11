package com.onelike.app.controller;

import com.onelike.app.exception.FilterNewsException;
import com.onelike.app.model.dto.FilterNewsDTO;
import com.onelike.app.service.ErrorService;
import com.onelike.app.service.FilterNewsConsoleService;
import com.onelike.app.support.AgentApiKey;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The agent write path into the publisher filter: one endpoint, add-only,
 * authenticated by the shared API key in onelike.news.filter.apikey.
 *
 * Its own controller rather than a fifth method on FilterNewsController, on
 * purpose. That class's javadoc declares it "the ADMIN/MODERATOR Console" and
 * every method there opens with the token check; a fifth method with a
 * different gate is how a future reader concludes the gate is class-wide and
 * then "tidies" either this method's key check or that class's per-method
 * checks away.
 *
 * Add-only is the whole design. There is deliberately no list, no toggle and
 * no delete here: the key is shared and carries no identity, so the blast
 * radius of a leaked key is "rows get added", which the Console can see and
 * undo, rather than "the filter list gets emptied", which it cannot.
 *
 * Three choices that look arbitrary and are not:
 *
 * - POST, not GET. Every mutation in this repo is a POST, and a GET that writes
 *   gets fetched by crawlers, link-preview bots and anything that retries a
 *   URL, each fetch silently inserting a row.
 *
 * - The name travels in the JSON body, not in the URL. A percent-encoded
 *   non-ASCII publisher name in a query string is the exact transport class
 *   CLAUDE.md's measurement-instruments table records as producing mojibake you
 *   cannot distinguish from a storage bug.
 *
 * - No @CrossOrigin, unlike all 19 other controllers here. CORS is enforced by
 *   browsers only, so it protects nothing on a server-to-server call, and a
 *   shared global key has no business in a web page where any visitor could
 *   read it out of the JavaScript. Omitting the annotation means a browser
 *   cannot read this endpoint's response cross-origin, which is the correct
 *   signal that no browser should be calling it.
 *
 * The key is never logged here. The filter name is, exactly as the Console logs
 * it; see AgentApiKey for why not even the key's length goes to the log.
 */
@Slf4j
@RequestMapping("/service/*")
@RestController
public class FilterNewsAgentController {

    private static final String CODE_APIKEY_INVALID = "filternews.apikey.invalid";
    private static final String CODE_NAME_INVALID = "filternews.name.invalid";

    private static final String CREATEDBY_AGENT = "agent";

    @Autowired
    FilterNewsConsoleService filterNewsConsoleService;

    @Autowired
    AgentApiKey agentApiKey;

    @Autowired
    ErrorService errorService;

    /**
     * Adds a publisher name, or reactivates the row that already carries it,
     * answering the response body of POST /service/filternews verbatim -
     * {added, reactivated, filter{...}} - because both endpoints are the same
     * call into FilterNewsConsoleService.createFilter. An already-present,
     * already-active name is an idempotent 200, so an agent that retries is
     * safe.
     *
     * The key is accepted BOTH as ?apiKey= and as the X-API-Key header, with
     * the query parameter winning when both are present. That is not a
     * preference, it is a copy: the sibling repo's
     * PublicApiController.createDiscussion uses those two names and that
     * precedence, so an agent already written against that API needs no new
     * convention here.
     *
     * The header exists because the query parameter is not safe to use. A query
     * string is written verbatim to logs/onelike.log: both properties files set
     * logging.level.org.springframework.web=DEBUG, and DispatcherServlet's
     * request log masks the parameter map but concatenates getQueryString()
     * with no guard. Prefer the header; the parameter is there for
     * compatibility.
     *
     * Both parameters are required = false. With required = true, a call that
     * sends neither would be answered by Spring's own 400 body instead of this
     * feature's 401 ApiError, and a caller branching on status plus errors[0]
     * would see neither.
     */
    @PostMapping(value = "/filternews/agent", produces = "application/json")
    public ResponseEntity<Object> addFilterNewsAsAgent(@RequestParam(value = "apiKey", required = false) String apiKey,
                                                       @RequestHeader(value = "X-API-Key", required = false) String headerKey,
                                                       @RequestBody FilterNewsDTO filterNewsDTO) {
        try {
            // Query parameter wins over the header, matching
            // PublicApiController.createDiscussion exactly.
            String presentedKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : headerKey;
            agentApiKey.requireValidKey(presentedKey);

            String name = FilterNewsConsoleService.normaliseName(filterNewsDTO.getName());

            log.debug("I am adding the news filter " + name + " for the agent");

            // "agent", never null: a null createdby would make this row
            // indistinguishable from one that predates the Console, which is
            // the attribution the column exists for - the same reason
            // ConsoleToken rejects a token with a blank sub.
            JSONObject result = filterNewsConsoleService.createFilter(name, CREATEDBY_AGENT);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (FilterNewsException e) {
            return createFilterNewsError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    /**
     * The exception message is the machine-readable code the caller branches
     * on, and ErrorService puts it in errors[0] verbatim; the status and the
     * human message are derived from it here so this endpoint answers the same
     * ApiError shape as the rest of the API.
     *
     * The throwable is passed on, never null: ErrorService builds ApiError's
     * third argument from error.getMessage() and that argument is the whole of
     * errors[], so a null throwable would make errors[0] the empty string.
     */
    private ResponseEntity<Object> createFilterNewsError(FilterNewsException e) {
        return errorService.createApiError(statusOf(e.getMessage()), messageOf(e.getMessage()), e);
    }

    private HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case CODE_APIKEY_INVALID -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * One message for every way the key can fail - missing, blank, wrong, or
     * the server's own key unset. Naming which one would tell a prober whether
     * they have the parameter name right.
     */
    private String messageOf(String code) {
        if (code == null) {
            return "Bad Request : Invalid filter request";
        }
        return switch (code) {
            case CODE_APIKEY_INVALID -> "Unauthorized : Missing or invalid API key";
            case CODE_NAME_INVALID -> "Bad Request : Invalid publisher name";
            default -> "Bad Request : Invalid filter request";
        };
    }
}
