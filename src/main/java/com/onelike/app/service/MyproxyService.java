package com.onelike.app.service;

import com.onelike.app.model.Breach;
import com.onelike.app.model.Myproxy;
import com.onelike.app.model.News;
import com.onelike.app.repository.MyproxyRepository;
import net.sourceforge.htmlunit.xpath.operations.Bool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class MyproxyService {

    @Autowired
    MyproxyRepository repository;

    @Transactional
    public Myproxy findById(Long id) throws Exception {
        Myproxy myProxy = null;
        Optional<Myproxy> existMyproxy  = repository.findById(id);
        if (existMyproxy.isPresent()) {
            myProxy = existMyproxy.get();
            return myProxy;
        }
        return myProxy;
    }

    public long getProxyNumber() {
        return repository.count();
    }

    public Myproxy findByIpAndPort(String ip, Integer port){
        return repository.findByIpAndPort(ip, port);
    }

    public List<Myproxy> findByActive(Boolean active){
        return repository.findByActive(active);
    }

    @Transactional
    public Myproxy save(Myproxy myproxy) throws Exception {
        try {
            repository.save(myproxy);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }
        return myproxy;
    }
}
