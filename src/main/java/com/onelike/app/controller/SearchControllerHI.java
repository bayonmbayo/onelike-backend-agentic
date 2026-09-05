package com.onelike.app.controller;

import com.onelike.app.model.Myproxy;
import com.onelike.app.model.News;
import com.onelike.app.service.MyproxyService;
import com.onelike.app.service.NewsService;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import net.amygdalum.stringsearchalgorithms.search.StringFinder;
import net.amygdalum.stringsearchalgorithms.search.StringMatch;
import net.amygdalum.stringsearchalgorithms.search.chars.AhoCorasick;
import net.amygdalum.util.io.CharProvider;
import net.amygdalum.util.io.StringCharProvider;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.onelike.app.controller.SearchController.*;
import static net.amygdalum.stringsearchalgorithms.search.MatchOption.LONGEST_MATCH;
import static net.amygdalum.stringsearchalgorithms.search.MatchOption.NON_OVERLAP;

@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/servicehi/*")
@RestController
public class SearchControllerHI {

    @Autowired
    NewsService newsService;

    @Value("${onelike.database.searchnews}")
    private Boolean searchNews;
    @GetMapping(value="/version")
    public String apiVersion() throws IOException {
        return "30 जून 2022 को बनाया गया एपीआई";
    }

    @GetMapping(value="/news",produces = "application/json")
    public ResponseEntity<String> getNews(HttpServletRequest request) throws Exception {
        // log.debug("ैं खबर दे रहा हूँ " + request.getRemoteAddr() + " [HINDI]");

        News news = newsService.retrieveHindiNews();

        return ResponseEntity.ok(news == null ? "{\"news\" : []}" : news.getFeed());
    }

    @Scheduled(fixedRate=900000)
    public void performJob() throws Exception {
        if(searchNews){
            saveNews("Hindi");
        }
    }

    @PostMapping(value="/searchnews",produces = "application/json")
    public String searchNews(@RequestBody String searchText, HttpServletRequest request) throws Exception {
        Boolean showAll = false;

        if(searchText.trim().equalsIgnoreCase("show__all")){
            showAll = true;
        }

        String data = performSearchNews("Hindi", searchText, showAll);

        log.debug("I am searching info about " + searchText + " for " + request.getRemoteAddr());

        return data;
    }
}
