package com.onelike.app.util;

import com.onelike.app.model.Myproxy;
import com.onelike.app.service.MyproxyService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single Authenticator for the whole process.
 *
 * Replaces every scattered Authenticator.setDefault(...) call. Those were a race:
 * the default is global, so the English feed job could overwrite the Arabic job's
 * credentials between openConnection() and connect(), making it authenticate
 * against the wrong proxy. This resolves credentials from the proxy actually
 * being contacted, so concurrent calls cannot collide.
 *
 * IMPORTANT: delete the Authenticator.setDefault(...) blocks in SearchController
 * (getWebsite x2, articlesOf, testImage, isOnline). Any one left in place will
 * uninstall this at runtime.
 */
@Slf4j
@Component
public class ProxyAuthenticator extends Authenticator {

    @Autowired
    MyproxyService _myproxyService;

    private static MyproxyService myproxyService;

    /** host:port -> credentials. Avoids a DB hit per connection. */
    private static final Map<String, PasswordAuthentication> CREDENTIALS = new ConcurrentHashMap<>();

    @PostConstruct
    private void initStaticDao() {
        myproxyService = this._myproxyService;

        // Required for Basic auth on the CONNECT tunnel used by https-through-proxy.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        Authenticator.setDefault(this);
        log.info("Global proxy authenticator installed");
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() != RequestorType.PROXY) {
            return null; // never hand proxy credentials to an origin server
        }

        String host = getRequestingHost();
        int port = getRequestingPort();

        return CREDENTIALS.computeIfAbsent(host + ":" + port, key -> {
            try {
                Myproxy proxy = myproxyService.findByIpAndPort(host, port);
                if (proxy == null) {
                    log.debug("No credentials on file for proxy {}", key);
                    return null;
                }
                return new PasswordAuthentication(
                        proxy.getProxyuser(), proxy.getProxypwd().toCharArray());
            } catch (Exception e) {
                log.debug("Could not load credentials for proxy {}: {}", key, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Call at the end of the static replaceInactiveProxies() so rotated
     * credentials are not served from a stale cache entry.
     */
    public static void evictCache() {
        CREDENTIALS.clear();
    }
}