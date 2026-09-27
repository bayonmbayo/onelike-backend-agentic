package com.onelike.app.model;

import lombok.Data;

import jakarta.persistence.*;
import java.util.Date;

@Entity
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private String uuid;
    private String firstname;
    private String name;
    private String email;
    private String displayname;
    private Date createdat;
    private Date updatedat;
}
