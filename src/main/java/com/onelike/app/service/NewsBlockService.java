package com.onelike.app.service;

import com.onelike.app.model.News;
import com.onelike.app.model.NewsBlock;
import com.onelike.app.repository.NewsBlockRepository;
import com.onelike.app.repository.NewsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsBlockService {
    @Autowired
    NewsBlockRepository repository;

    @Transactional
    public NewsBlock save(NewsBlock news) throws Exception {
        try {
            repository.save(news);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return news;
    }

    @Transactional
    public NewsBlock findBySocialMediaAndLanguage(String socialMedia, String language) throws Exception {
        return repository.findBySocialMediaAndLanguage(socialMedia, language);
    }
}
