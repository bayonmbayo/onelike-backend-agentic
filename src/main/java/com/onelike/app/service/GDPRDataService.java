package com.onelike.app.service;

import com.onelike.app.model.GDPRData;
import com.onelike.app.model.Myproxy;
import com.onelike.app.repository.GDPRDataRepository;
import com.onelike.app.support.EMail;
import com.onelike.app.support.EmailTemplates;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class GDPRDataService {
    @Autowired
    GDPRDataRepository repository;
    @Autowired
    EMail email;
    @Value("${onelike.email.team}")
    private String mailTeam;
    @Transactional
    public GDPRData save(GDPRData gdprData) throws Exception {
        try {
            gdprData.setRequesttime(new Date());
            repository.save(gdprData);
        }catch(Exception e) {
            e.printStackTrace();
            throw new Exception("Error : Can't be saved.", e);
        }

        generateEmailToUser(gdprData);
        generateEmailToTeam(gdprData);

        return gdprData;
    }

    private void generateEmailToUser(GDPRData gdprData) throws Exception {
        String text = "" + EmailTemplates.GDPRConfirmation;

        text = text.replaceAll("\\$FIRSTNAME\\$",gdprData.getFirstname()).replaceAll("\\$LASTNAME\\$",gdprData.getLastname());

        email.sendEmailHTML(gdprData.getEmail(), "Confirmation of Your GDPR Request", text);
    }

    private void generateEmailToTeam(GDPRData gdprData) throws Exception {
        String text = "" + EmailTemplates.GDPRRequestNotification;

        text = text.replaceAll("\\$FIRSTNAME\\$",gdprData.getFirstname())
                .replaceAll("\\$LASTNAME\\$",gdprData.getLastname())
                .replaceAll("\\$EMAIL\\$",gdprData.getEmail())
                .replaceAll("\\$TIME\\$", String.valueOf(gdprData.getRequesttime()))
                .replaceAll("\\$TYPE\\$",gdprData.getRequesttype());

        email.sendEmailHTML(mailTeam, "New GDPR Request Notification", text);
    }
}
