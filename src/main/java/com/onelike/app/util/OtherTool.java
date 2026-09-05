package com.onelike.app.util;

import net.amygdalum.stringsearchalgorithms.search.StringFinder;
import net.amygdalum.stringsearchalgorithms.search.StringMatch;
import net.amygdalum.stringsearchalgorithms.search.chars.AhoCorasick;
import net.amygdalum.util.io.CharProvider;
import net.amygdalum.util.io.StringCharProvider;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.amygdalum.stringsearchalgorithms.search.MatchOption.LONGEST_MATCH;
import static net.amygdalum.stringsearchalgorithms.search.MatchOption.NON_OVERLAP;

public class OtherTool {

    public static final List<Long> times = Arrays.asList(
            TimeUnit.DAYS.toMillis(365),
            TimeUnit.DAYS.toMillis(30),
            TimeUnit.DAYS.toMillis(1),
            TimeUnit.HOURS.toMillis(1),
            TimeUnit.MINUTES.toMillis(1),
            TimeUnit.SECONDS.toMillis(1));
    public static final List<String> timesString = Arrays.asList("y", "m", "d", "h", "min", "s");
    public static String toDuration(String date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH);
        LocalDateTime localDate = LocalDateTime.parse(date, formatter);
        long timeInSeconds = LocalDateTime.now().atZone(ZoneOffset.UTC).toInstant().toEpochMilli() / 1000 - localDate.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() / 1000;

        double years = timeInSeconds / (60.0 * 60 * 24 * 365);
        if (years >= 1) {
            return String.format(Locale.ENGLISH, "%.1f y", years);
        }

        double months = timeInSeconds / (60.0 * 60 * 24 * 30);
        if (months >= 1) {
            return String.format(Locale.ENGLISH, "%.1f M", months);
        }

        double days = timeInSeconds / (60.0 * 60 * 24);
        if (days >= 1) {
            return String.format(Locale.ENGLISH, "%.1f d", days);
        }

        double hours = timeInSeconds / (60.0 * 60);
        if (hours >= 1) {
            return String.format(Locale.ENGLISH, "%.1f h", hours);
        }

        double minutes = timeInSeconds / 60.0;
        if (minutes >= 1) {
            return String.format(Locale.ENGLISH, "%.1f min", minutes);
        }

        if(years < 0 || months < 0 || days < 0 || hours < 0 || minutes < 0){
            return "negative";
        }

        // For seconds, no need to convert
        return String.format(Locale.ENGLISH, "%.1f s", (double) timeInSeconds);
    }

    public static Date convertStringToDate(String dateString){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX");

        ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateString, formatter);

        // Convert ZonedDateTime to java.util.Date
        Date date = Date.from(zonedDateTime.toInstant());

        return date;
    }

    public static boolean urlEndsWithPublic(String url) {
        return url.matches(".*public/?$");
    }
    public static boolean urlEndsWithProfil(String url) {
        return url.matches(".*profil/?$");
    }

    public static boolean urlEndsWithPreview(String url) {
        return url.matches(".*preview/?$");
    }
    public static boolean urlEndsWithProfilcard(String url) {
        return url.matches(".*profilcard/?$");
    }
    public static boolean containsWhitespace(String str) {
        if(str == null){
            return true;
        }

        if(str.equalsIgnoreCase("")){
            return true;
        }

        Pattern pattern = Pattern.compile("\\s");
        Matcher matcher = pattern.matcher(str);
        return matcher.find(); // Returns true if a whitespace character is found
    }

    public static boolean isBlank(String input) {
        return input == null || input.trim().isEmpty();
    }

    public static int calculateAge(Date birthDate) {
        if (birthDate == null) {
            return 0; // Handle null case or throw an IllegalArgumentException
        }
        // Create a calendar object for the birth date
        Calendar birthCal = Calendar.getInstance();
        birthCal.setTime(birthDate);

        // Create a calendar object for the current date
        Calendar todayCal = Calendar.getInstance();

        // Calculate the difference in years
        int yearsDifference = todayCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR);

        // Check if the current date is before the birth date in the current year
        // If so, subtract 1 from the age
        if (todayCal.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) {
            yearsDifference--;
        }

        return yearsDifference;
    }

    public static boolean isMatching(List<String> words, String sentence) {
        if(sentence == null || isBlank(sentence)){
            return false;
        }

        AhoCorasick stringSearch = new AhoCorasick(words);
        CharProvider text = new StringCharProvider(sentence.toLowerCase(), 0);
        StringFinder finder = stringSearch.createFinder(text, LONGEST_MATCH, NON_OVERLAP);
        List<StringMatch> all = finder.findAll();

        if (all.size() == 0) {
            return false;
        }

        return true;
    }

    public static boolean endsWith(String input, String end) {
        if (input == null) {
            return false; // or throw an IllegalArgumentException
        }
        return input.endsWith(end);
    }
}

