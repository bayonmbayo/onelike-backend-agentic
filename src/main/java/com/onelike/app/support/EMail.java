package com.onelike.app.support;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class EMail {

    @Value("${onelike.email.from}")
    private String mailFrom;

    @Autowired
    private JavaMailSender javaMailSender;

    public static boolean checkEmail(String email) {
        //RFC 5322 for Email Validation
        String regexPattern = "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$";

        return Pattern.compile(regexPattern)
                .matcher(email)
                .matches();
    }

    public void sendEmailWithAttachment(String to, String subject, String text, List<File> files, boolean isHTML) throws MessagingException, IOException {

        MimeMessage msg = javaMailSender.createMimeMessage();

        // true = multipart message
        MimeMessageHelper helper = new MimeMessageHelper(msg, true);

        helper.setTo(to);
        helper.setFrom(mailFrom);
        helper.setSubject(subject);

        helper.setText(text, isHTML);

        if(files != null && !files.isEmpty()){
            for(File f: files)
                helper.addAttachment(f.getName(), f);
        }


        javaMailSender.send(msg);
    }

    public void sendEmailHTML(String to, String subject, String text)  throws MessagingException, IOException {
        sendEmailWithAttachment(to, subject, text, null, true);
    }

    public void sendEmailHTMLWithAttachment(String to, String subject, String text, List<File> files)  throws MessagingException, IOException {
        sendEmailWithAttachment(to, subject, text, files, true);
    }



}
