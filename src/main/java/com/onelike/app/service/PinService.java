package com.onelike.app.service;

import com.onelike.app.model.InfoNews;
import com.onelike.app.model.Pin;
import com.onelike.app.model.User;
import com.onelike.app.model.dto.PinDTO;
import com.onelike.app.repository.InfoNewsRepository;
import com.onelike.app.repository.PinRepository;
import com.onelike.app.repository.UrlPinCount;
import com.onelike.app.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
public class PinService {
    @Autowired
    PinRepository pinRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    InfoNewsRepository infoNewsRepository;

    public Boolean pin(PinDTO pinDTO) throws ParseException {
        User user = userRepository.findUserByEmail(pinDTO.getEmail());
        if (user == null) {
            user = new User();
            user.setUuid(pinDTO.getUuid());
            user.setFirstname(pinDTO.getFirstname());
            user.setName(pinDTO.getName());
            user.setDisplayname(pinDTO.getDisplayname());
            user.setEmail(pinDTO.getEmail());
            user.setCreatedat(new Date());
            userRepository.save(user);
        } else {
            user.setUuid(pinDTO.getUuid());
            user.setFirstname(pinDTO.getFirstname());
            user.setName(pinDTO.getName());
            user.setDisplayname(pinDTO.getDisplayname());
            user.setUpdatedat(new Date());
            userRepository.save(user);
        }

        InfoNews infoNews = infoNewsRepository.findByUrl(pinDTO.getUrl());
        if (infoNews == null) {
            infoNews = new InfoNews();
            infoNews.setUrl(pinDTO.getUrl());
            infoNews.setTitle(pinDTO.getTitle());
            infoNews.setSource(pinDTO.getSource());
            infoNews.setPublishedat(parsePublishedAt(pinDTO.getPublishedat()));
            infoNews.setSavedat(new Date());
            infoNewsRepository.save(infoNews);
        }

        Pin pin = pinRepository.getPin(user, infoNews);
        if (pin == null) {
            pin = new Pin();
            pin.setInfoNews(infoNews);
            pin.setUser(user);
        }
        pin.setPinned(pinDTO.getPinned());
        pin.setPinnedat(new Date());
        pinRepository.save(pin);

        return pin.getPinned();
    }

    public List<InfoNews> getMyPinnedInfoNews(User user) {
        List<Pin> pins = pinRepository.getMyPinned(true, user);
        List<InfoNews> result = new ArrayList<>();
        for (Pin p : pins) {
            result.add(p.getInfoNews());
        }
        return result;
    }

    public User findByUuid(String uuid) {
        return userRepository.findUserByUuid(uuid);
    }

    public List<UrlPinCount> getPinCounts() {
        return pinRepository.getPinCountsByUrl();
    }

    /**
     * Java's SimpleDateFormat resolves ambiguous 3-letter zone abbreviations (e.g. "CST") using its
     * own built-in table, which defaults "CST" to US Central Time rather than China Standard Time -
     * the zone this app's upstream feed actually uses (confirmed alongside "GMT+08:00" timestamps
     * from the same source). Normalize known-ambiguous abbreviations to an explicit offset before
     * parsing so the result is deterministic instead of silently 14 hours off.
     */
    private static String normalizeTimeZoneAbbreviation(String s) {
        return s.replace(" CST ", " GMT+08:00 ");
    }

    private Date parsePublishedAt(String s) throws ParseException {
        return new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH).parse(normalizeTimeZoneAbbreviation(s));
    }
}
