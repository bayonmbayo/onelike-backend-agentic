package com.onelike.app.controller;

import com.onelike.app.exception.PinException;
import com.onelike.app.model.InfoNews;
import com.onelike.app.model.User;
import com.onelike.app.model.dto.PinDTO;
import com.onelike.app.repository.UrlPinCount;
import com.onelike.app.service.ErrorService;
import com.onelike.app.service.PinService;
import com.onelike.app.util.OtherTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/*")
@RestController
public class PinController {

    @Autowired
    PinService pinService;

    @Autowired
    ErrorService errorService;

    @PostMapping(value = "/pin", produces = "application/json")
    public ResponseEntity<Object> pinInfoNews(@RequestBody PinDTO pinDTO, HttpServletRequest request) {
        try {
            if (pinDTO.getUrl() == null || pinDTO.getEmail() == null) {
                return errorService.createApiError(HttpStatus.BAD_REQUEST, "Bad Request : Can't pin", new PinException("Missing url or email"));
            }

            Boolean pinned = pinService.pin(pinDTO);

            log.debug("I am pinning " + pinDTO.getUrl() + " for " + pinDTO.getEmail());

            return new ResponseEntity<>(Map.of("pinned", pinned), new HttpHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    @GetMapping(value = "/mypinnednews", produces = "application/json")
    public ResponseEntity<Object> getMyPinnedNews(@RequestParam("uuid") String uuid) {
        try {
            User user = pinService.findByUuid(uuid);

            if (user == null) {
                return new ResponseEntity<>(new ArrayList<>(), new HttpHeaders(), HttpStatus.OK);
            }

            List<InfoNews> pinnedNews = pinService.getMyPinnedInfoNews(user);

            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");

            List<Map<String, Object>> mappedList = new ArrayList<>();
            for (InfoNews infoNews : pinnedNews) {
                String publishedAtIso = infoNews.getPublishedat() != null
                        ? isoFormat.format(infoNews.getPublishedat())
                        : null;
                String timeago = publishedAtIso != null ? OtherTool.toDuration(publishedAtIso) : null;

                Map<String, Object> item = new java.util.HashMap<>();
                item.put("id", infoNews.getId());
                item.put("url", infoNews.getUrl());
                item.put("title", infoNews.getTitle());
                item.put("source", infoNews.getSource());
                item.put("image", infoNews.getImage());
                item.put("publishedat", publishedAtIso);
                item.put("timeago", timeago);
                item.put("pinned", true);
                mappedList.add(item);
            }

            return new ResponseEntity<>(mappedList, new HttpHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }

    // Public (not user-scoped): how many users have pinned each article.
    // One bulk call for every article ever pinned, not one call per article -
    // the frontend fetches this once and looks counts up locally.
    @GetMapping(value = "/pincount", produces = "application/json")
    public ResponseEntity<Object> getPinCounts() {
        try {
            List<UrlPinCount> counts = pinService.getPinCounts();

            List<Map<String, Object>> mappedList = new ArrayList<>();
            for (UrlPinCount c : counts) {
                Map<String, Object> item = new java.util.HashMap<>();
                item.put("url", c.getUrl());
                item.put("count", c.getCount());
                mappedList.add(item);
            }

            return new ResponseEntity<>(mappedList, new HttpHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error : Problem at server", e);
        }
    }
}
