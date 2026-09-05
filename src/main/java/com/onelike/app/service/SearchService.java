package com.onelike.app.service;

import com.onelike.app.model.Search;
import com.onelike.app.repository.SearchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {

    @Autowired
    SearchRepository repository;

    @Transactional
    public Search save(Search search) throws Exception {
        try {
            repository.save(search);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : can't be saved", e);
        }
        return search;
    }

    @Transactional
    public Search findSearchByText(String searchText) throws Exception {
        return repository.findBySearchText(searchText);
    }
}
