package com.onelike.app.controller;

import com.onelike.app.exception.FilterNewsException;
import com.onelike.app.model.dto.FilterNewsActiveDTO;
import com.onelike.app.model.dto.FilterNewsDTO;
import com.onelike.app.model.dto.FilterNewsDeleteDTO;
import com.onelike.app.service.ErrorService;
import com.onelike.app.service.FilterNewsConsoleService;
import com.onelike.app.support.ConsoleToken;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The ADMIN/MODERATOR Console for the publisher filter: list every FilterNews
 * row, add one, switch one on or off, delete one.
 *
 * Bodies are hand-built with net.minidev.json and returned as a String, like
 * UserNewsContainerController: minidev writes a java.util.Date with toString(),
 * which is the format the rest of the news endpoints already emit - letting
 * Jackson serialise the same object would silently turn it into epoch millis
 * and break the client's parsing.
 *
 * Two things here are easy to "tidy" into a bug:
 *
 * - The Authorization header is required = false on purpose. With required =
 *   true, a call with no header would be answered by Spring with its own 400
 *   body instead of this feature's 401 ApiError, and the client branches on
 *   status plus errors[0].
 *
 * - No mapping declares consumes. The client's authHeaderWithJSON() sends
 *   Content-Type: application/json even on the bodyless GET; Spring ignores it
 *   only as long as the mapping has no consumes. Adding one breaks that GET.
 */
@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/*")
@RestController
public class FilterNewsController {

    private static final String CODE_UNAUTHORIZED = "filternews.unauthorized";
    private static final String CODE_FORBIDDEN = "filternews.forbidden";
    private static final String CODE_NAME_INVALID = "filternews.name.invalid";
    private static final String CODE_ID_MISSING = "filternews.id.missing";
    private static final String CODE_ACTIVE_MISSING = "filternews.active.missing";
    private static final String CODE_NOTFOUND = "filternews.notfound";

    @Autowired
    FilterNewsConsoleService filterNewsConsoleService;

    @Autowired
    ConsoleToken consoleToken;

    @Autowired
    ErrorService errorService;

    /**
     * Every row, active and inactive, newest first. No parameters, no body.
     */
    @GetMapping(value = "/filternews", produces = "application/json")
    public ResponseEntity<Object> getFilterNews(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        try {
            consoleToken.requireAdminOrModerator(authorization);

            log.debug("I am listing the news filters");

            JSONObject result = filterNewsConsoleService.allFilters();

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (FilterNewsException e) {
            return createFilterNewsError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    /**
     * Adds a publisher name, or reactivates the row that already carries it.
     * A name that is already present and already active is an idempotent 200,
     * not an error, so a double-submitting popup is safe.
     */
    @PostMapping(value = "/filternews", produces = "application/json")
    public ResponseEntity<Object> addFilterNews(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                @RequestBody FilterNewsDTO filterNewsDTO) {
        try {
            // createdby is the token's subject and nothing else: it must not be
            // client-assertable, which is why no DTO here carries an identity.
            String createdby = consoleToken.requireAdminOrModerator(authorization);

            // Trim and the three name rules live in the service, so the agent
            // write path enforces byte-identical validation from one place
            // rather than a second copy of these checks.
            String name = FilterNewsConsoleService.normaliseName(filterNewsDTO.getName());

            log.debug("I am adding the news filter " + name);

            JSONObject result = filterNewsConsoleService.createFilter(name, createdby);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (FilterNewsException e) {
            return createFilterNewsError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    /**
     * The reversible switch. active is checked for null rather than defaulted,
     * so a client that forgets the field gets filternews.active.missing instead
     * of silently switching the filter off.
     */
    @PostMapping(value = "/filternews/active", produces = "application/json")
    public ResponseEntity<Object> setFilterNewsActive(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                      @RequestBody FilterNewsActiveDTO filterNewsActiveDTO) {
        try {
            consoleToken.requireAdminOrModerator(authorization);

            if (filterNewsActiveDTO.getId() == null) {
                throw new FilterNewsException(CODE_ID_MISSING);
            }
            if (filterNewsActiveDTO.getActive() == null) {
                throw new FilterNewsException(CODE_ACTIVE_MISSING);
            }

            log.debug("I am switching the news filter " + filterNewsActiveDTO.getId() + " to " + filterNewsActiveDTO.getActive());

            JSONObject result = filterNewsConsoleService.updateActive(filterNewsActiveDTO.getId(), filterNewsActiveDTO.getActive());

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (FilterNewsException e) {
            return createFilterNewsError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    /**
     * Hard delete, available to MODERATOR as well as ADMIN. The soft option is
     * /filternews/active and both exist deliberately.
     */
    @PostMapping(value = "/filternews/delete", produces = "application/json")
    public ResponseEntity<Object> deleteFilterNews(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                   @RequestBody FilterNewsDeleteDTO filterNewsDeleteDTO) {
        try {
            consoleToken.requireAdminOrModerator(authorization);

            if (filterNewsDeleteDTO.getId() == null) {
                throw new FilterNewsException(CODE_ID_MISSING);
            }

            log.debug("I am deleting the news filter " + filterNewsDeleteDTO.getId());

            filterNewsConsoleService.deleteFilter(filterNewsDeleteDTO.getId());

            JSONObject result = new JSONObject();
            result.put("deleted", true);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (FilterNewsException e) {
            return createFilterNewsError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    /**
     * The exception message is the machine-readable code the client branches on,
     * and ErrorService puts it in errors[0] verbatim; the human message and the
     * HTTP status are derived from it here so all four endpoints answer the same
     * ApiError shape as the rest of the API.
     *
     * The throwable is passed on, never null: ErrorService builds ApiError's
     * third argument from error.getMessage(), and that argument is the whole of
     * errors[]. A null throwable would make errors[0] the empty string and
     * collapse the client's entire error branch.
     */
    private ResponseEntity<Object> createFilterNewsError(FilterNewsException e) {
        return errorService.createApiError(statusOf(e.getMessage()), messageOf(e.getMessage()), e);
    }

    private HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case CODE_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case CODE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CODE_NOTFOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String messageOf(String code) {
        if (code == null) {
            return "Bad Request : Invalid filter request";
        }
        return switch (code) {
            case CODE_UNAUTHORIZED -> "Unauthorized : Missing or invalid token";
            case CODE_FORBIDDEN -> "Forbidden : Insufficient privileges";
            case CODE_NAME_INVALID -> "Bad Request : Invalid publisher name";
            case CODE_ID_MISSING -> "Bad Request : Missing id";
            case CODE_ACTIVE_MISSING -> "Bad Request : Missing active";
            case CODE_NOTFOUND -> "Not Found : No such filter";
            default -> "Bad Request : Invalid filter request";
        };
    }
}
