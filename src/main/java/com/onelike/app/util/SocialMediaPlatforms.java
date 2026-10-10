package com.onelike.app.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The built-in social-media platforms of the Social News page, in the order the
 * client renders them by default: the index in this list IS the default
 * {@code position} of a PLATFORM container (0-19).
 *
 * Moved here out of SearchController, which used to keep the same list in a
 * private static field assigned in @PostConstruct, so that the container
 * feature and the 5-minute feed job share exactly one definition.
 *
 * The casing is part of the contract, not cosmetic: the client uses each name
 * as the key for its icon and image file names, UrlWebsite / Description switch
 * on it, and NewsBlock.socialmedia stores it verbatim. Do not re-case, do not
 * sort, and do not re-add "Xing" (deliberately retired - UrlWebsite still has
 * its commented-out branch).
 */
public class SocialMediaPlatforms {

    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            "Twitter",
            "Facebook",
            "Instagram",
            "Telegram",
            "Reddit",
            "WhatsApp",
            "Snapchat",
            "TikTok",
            "Discord",
            "Tinder",
            "Twitch",
            "Linkedin",
            // "Xing",
            "Vkontakte",
            "Wechat",
            "YouTube",
            "Pinterest",
            "Github",
            "Signal",
            "Patreon",
            "Onlyfans"
    ));

    /**
     * True when the given text is one of the built-in platform names, ignoring
     * case. A user theme may not use such a title: NewsBlock is keyed by the
     * display title and MySQL's default collation is case-insensitive, so a
     * theme called "twitter" would overwrite the Twitter platform's feed.
     */
    public static boolean isPlatform(String text) {
        if (text == null) {
            return false;
        }
        for (String platform : ALL) {
            if (platform.equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }
}
