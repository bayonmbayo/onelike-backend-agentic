package com.onelike.app.service;

import com.onelike.app.model.News;
import com.onelike.app.model.RoosBreakerPlayer;
import com.onelike.app.model.Search;
import com.onelike.app.repository.RoosBreakerPlayerRepository;
import com.onelike.app.repository.SearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoosBreakerPlayerService {

    @Autowired
    RoosBreakerPlayerRepository repository;

    @Transactional
    public RoosBreakerPlayer findRoosBreakerPlayerByUsername(String username) throws Exception {
        return repository.findByUsername(username);
    }

    @Transactional
    public RoosBreakerPlayer save(RoosBreakerPlayer roosBreakerPlayer) throws Exception {
        try {
            repository.save(roosBreakerPlayer);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return roosBreakerPlayer;
    }
}
