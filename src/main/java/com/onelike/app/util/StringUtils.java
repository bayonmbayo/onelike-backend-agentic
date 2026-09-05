package com.onelike.app.util;

import org.apache.commons.lang3.RandomStringUtils;

public class StringUtils {

    private static final String characters_long = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~`!@#$%^&*()-_=+[{]}\\|;:\'\",<.>/?";
    private static final String characters_short = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int DEFAULT_COUNT =10;

    public static String createRandomErrorNumber() {
        return createRandomString( 7, true);
    }

    public static String createRandomString(int count, boolean lowerCase) {
        if(count == 0)
            count = DEFAULT_COUNT;

        if(lowerCase){
            return RandomStringUtils.random( count, characters_short );
        }else{
            return RandomStringUtils.random( count, characters_long );
        }
    }
}
