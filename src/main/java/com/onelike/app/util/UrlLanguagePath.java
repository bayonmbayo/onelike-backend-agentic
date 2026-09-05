package com.onelike.app.util;

public class UrlLanguagePath {

    public static String getUrlLanguagePath(String language){
        return switch (language){
            case "Arabic" -> "&hl=ar&gl=EG&ceid=EG:ar";
            case "Chinese" -> "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
            case "Deutsch" -> "&hl=de&gl=DE&ceid=DE:de";
            case "Spanish" -> "&hl=es-ES&gl=ES&ceid=ES:es";
            case "Francais" -> "&hl=fr&gl=FR&ceid=FR:fr";
            case "Greek" -> "&hl=el&gl=GR&ceid=GR:el";
            case "Hindi" -> "&hl=hi&gl=IN&ceid=IN:hi";
            case "Indonesian" -> "&hl=id&gl=ID&ceid=ID:id";
            case "Italian" -> "&hl=it&gl=IT&ceid=IT:it";
            case "Japanese" -> "&hl=ja&gl=JP&ceid=JP:ja";
            case "Dutch" -> "&hl=nl&gl=NL&ceid=NL:nl";
            case "Portuguese" -> "&hl=pt-PT&gl=PT&ceid=PT:pt-150";
            case "Russian" -> "&hl=ru&gl=RU&ceid=RU:ru";
            case "Turkish" -> "&hl=tr&gl=TR&ceid=TR:tr";
            default -> "&hl=en-US&gl=US&ceid=US:en";
        };
    }
}
