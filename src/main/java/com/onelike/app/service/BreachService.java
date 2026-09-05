package com.onelike.app.service;

import com.onelike.app.model.Breach;
import com.onelike.app.model.News;
import com.onelike.app.model.Search;
import com.onelike.app.repository.BreachRepository;
import com.onelike.app.repository.NewsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class BreachService {

    @Autowired
    BreachRepository repository;

    @Transactional
    public Breach save(Breach breach) throws Exception {
        try {
            repository.save(breach);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return breach;
    }

    @Transactional
    public Breach findById(Long id) throws Exception {
        Breach breach = null;
        Optional<Breach> existBreach  = repository.findById(id);
        if (existBreach.isPresent()) {
            breach = existBreach.get();
            return breach;
        }
        return breach;
    }

    @Transactional
    public Breach findByAccount(String account) throws Exception {
        return repository.findByAccount(account);
    }
}
