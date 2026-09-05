package com.onelike.app.service;

import com.onelike.app.model.Quote;
import com.onelike.app.model.Search;
import com.onelike.app.repository.QuoteRepository;
import com.onelike.app.repository.SearchRepository;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QuoteService {

    @Autowired
    QuoteRepository repository;

    @Cacheable(value = "quotes")
    @Transactional
    public List<Quote> getAllQuotes() {
        return repository.findAll();
    }
}
