package com.onelike.app.controller;

import com.onelike.app.exception.ContainerException;
import com.onelike.app.model.dto.ContainerDTO;
import com.onelike.app.model.dto.ContainerDeleteDTO;
import com.onelike.app.model.dto.ContainerOrderDTO;
import com.onelike.app.service.ErrorService;
import com.onelike.app.service.UserNewsContainerService;
import com.onelike.app.util.SocialMediaPlatforms;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The signed-in user's Social News cards: list, add a theme, delete a theme,
 * reorder everything.
 *
 * Bodies are hand-built with net.minidev.json and returned as a String, like
 * the rest of the news endpoints: minidev writes a java.util.Date with
 * toString(), which is the last_update format /service/news already emits -
 * letting Jackson serialise the same object would silently turn it into epoch
 * millis and break the client's parsing.
 */
@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/*")
@RestController
public class UserNewsContainerController {

    private static final String CODE_IDENTITY_MISSING = "container.identity.missing";
    private static final String CODE_TITLE_INVALID = "container.title.invalid";
    private static final String CODE_TITLE_RESERVED = "container.title.reserved";
    private static final String CODE_ID_MISSING = "container.id.missing";
    private static final String CODE_ORDER_EMPTY = "container.order.empty";
    private static final String CODE_IDENTITY_MISMATCH = "container.identity.mismatch";
    private static final String CODE_NOTFOUND = "container.notfound";
    private static final String CODE_LIMIT_REACHED = "container.limit.reached";

    @Autowired
    UserNewsContainerService containerService;

    @Autowired
    ErrorService errorService;

    /**
     * A missing uuid is deliberately allowed here (unlike /mypinnednews): a
     * signed-out visitor asks the same question and must get the 20 defaults
     * with seeded:false, not a 400.
     */
    @GetMapping(value = "/mycontainers", produces = "application/json")
    public ResponseEntity<Object> getMyContainers(@RequestParam(value = "uuid", required = false) String uuid,
                                                  @RequestParam(value = "language", required = false) String language) {
        try {
            log.debug("I am listing containers for " + uuid);

            JSONObject result = containerService.myContainers(uuid, language);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (ContainerException e) {
            return createContainerError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    @PostMapping(value = "/container", produces = "application/json")
    public ResponseEntity<Object> addContainer(@RequestBody ContainerDTO containerDTO) {
        try {
            requireIdentity(containerDTO.getUuid(), containerDTO.getEmail());

            String title = containerDTO.getTitle() == null ? "" : containerDTO.getTitle().trim();
            if (title.isEmpty() || title.length() > UserNewsContainerService.TITLE_MAX_LENGTH || hasControlCharacter(title)) {
                throw new ContainerException(CODE_TITLE_INVALID);
            }
            // The theme title is the NewsBlock key and MySQL compares it
            // case-insensitively, so a theme named "twitter" would overwrite the
            // Twitter platform's feed for every language.
            if (SocialMediaPlatforms.isPlatform(title)) {
                throw new ContainerException(CODE_TITLE_RESERVED);
            }

            log.debug("I am adding the container " + title + " for " + containerDTO.getEmail());

            JSONObject result = containerService.createContainer(containerDTO, title);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (ContainerException e) {
            return createContainerError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    @PostMapping(value = "/container/delete", produces = "application/json")
    public ResponseEntity<Object> deleteContainer(@RequestBody ContainerDeleteDTO containerDeleteDTO) {
        try {
            requireIdentity(containerDeleteDTO.getUuid(), containerDeleteDTO.getEmail());

            if (containerDeleteDTO.getId() == null) {
                throw new ContainerException(CODE_ID_MISSING);
            }

            log.debug("I am deleting the container " + containerDeleteDTO.getId() + " for " + containerDeleteDTO.getEmail());

            containerService.deleteContainer(containerDeleteDTO);

            JSONObject result = new JSONObject();
            result.put("deleted", true);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (ContainerException e) {
            return createContainerError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    @PostMapping(value = "/containers/order", produces = "application/json")
    public ResponseEntity<Object> orderContainers(@RequestBody ContainerOrderDTO containerOrderDTO) {
        try {
            requireIdentity(containerOrderDTO.getUuid(), containerOrderDTO.getEmail());

            if (containerOrderDTO.getContainers() == null || containerOrderDTO.getContainers().isEmpty()) {
                throw new ContainerException(CODE_ORDER_EMPTY);
            }

            log.debug("I am reordering containers for " + containerOrderDTO.getEmail());

            JSONArray containers = containerService.reorder(containerOrderDTO);

            JSONObject result = new JSONObject();
            result.put("ordered", true);
            result.put("containers", containers);

            return new ResponseEntity<>(result.toJSONString(), new HttpHeaders(), HttpStatus.OK);
        } catch (ContainerException e) {
            return createContainerError(e);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    private void requireIdentity(String uuid, String email) throws ContainerException {
        if (uuid == null || uuid.trim().isEmpty() || email == null || email.trim().isEmpty()) {
            throw new ContainerException(CODE_IDENTITY_MISSING);
        }
    }

    private boolean hasControlCharacter(String title) {
        for (int i = 0; i < title.length(); i++) {
            if (Character.isISOControl(title.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The exception message is the machine-readable code the client branches on,
     * and ErrorService puts it in errors[0] verbatim; the human message and the
     * HTTP status are derived from it here so all four endpoints answer the same
     * ApiError shape as the rest of the API.
     */
    private ResponseEntity<Object> createContainerError(ContainerException e) {
        return errorService.createApiError(statusOf(e.getMessage()), messageOf(e.getMessage()), e);
    }

    private HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case CODE_IDENTITY_MISMATCH -> HttpStatus.FORBIDDEN;
            case CODE_NOTFOUND -> HttpStatus.NOT_FOUND;
            case CODE_LIMIT_REACHED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private String messageOf(String code) {
        if (code == null) {
            return "Bad Request : Invalid container request";
        }
        return switch (code) {
            case CODE_IDENTITY_MISSING -> "Bad Request : Missing uuid or email";
            case CODE_TITLE_INVALID -> "Bad Request : Invalid title";
            case CODE_TITLE_RESERVED -> "Bad Request : Title is reserved";
            case CODE_ID_MISSING -> "Bad Request : Missing id";
            case CODE_ORDER_EMPTY -> "Bad Request : Missing containers";
            case CODE_IDENTITY_MISMATCH -> "Forbidden : Identity mismatch";
            case CODE_NOTFOUND -> "Not Found : No such container";
            case CODE_LIMIT_REACHED -> "Conflict : Theme limit reached";
            default -> "Bad Request : Invalid container request";
        };
    }
}
