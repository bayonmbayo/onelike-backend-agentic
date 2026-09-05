package com.onelike.app.model;

import lombok.Data;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import jakarta.persistence.*;
import java.util.Date;
import java.util.List;

@Entity
@Data
public class Breach {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private Date searched;
    private String ipaddress;
    private Integer times;
    private Integer breaches;
    private String account;
    private boolean successful;

    @OneToMany(mappedBy = "breach", cascade = CascadeType.ALL)
    @LazyCollection(LazyCollectionOption.FALSE)
    private List<Site> sites;

    public void addSite(Site site){
        sites.add(site);
    }

    public void delSite(Site site){
        sites.remove(site);
    }

}
