package com.onelike.app.util;

public class UrlWebsite {
    public static String getWebsiteUrl(String searchText){

        return switch (searchText) {
            case "Twitter" -> "https://www.x.com";
            case "Facebook" -> "https://www.facebook.com";
            case "Instagram" -> "https://www.instagram.com";
            case "Telegram" -> "https://www.telegram.org";
            case "Reddit" -> "https://www.reddit.com";
            case "WhatsApp" -> "https://www.whatsapp.com";
            case "Snapchat" -> "https://www.snapchat.com";
            case "TikTok" -> "https://www.tiktok.com";
            case "Discord" -> "https://www.discord.com";
            case "Tinder" -> "https://www.tinder.com";
            case "Signal" -> "https://www.signal.org";
            case "Linkedin" -> "https://www.linkedin.com";
            case "Vkontakte" -> "https://www.vk.com/";
            // case "Xing" -> "https://www.xing.com";
            case "Pinterest" -> "https://www.pinterest.com";
            case "Wechat" -> "https://www.wechat.com/";
            case "YouTube" -> "https://www.youtube.com";
            case "Github" -> "https://www.github.com";
            case "Twitch" -> "https://www.twitch.tv/";
            case "Patreon" -> "https://www.patreon.com/";
            case "Onlyfans" -> "https://onlyfans.com/";
            default -> "https://www.google.com";
        };
    }
}
