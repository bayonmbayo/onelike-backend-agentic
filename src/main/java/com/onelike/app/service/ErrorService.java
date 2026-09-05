package com.onelike.app.service;

import com.onelike.app.error.ApiError;
import com.onelike.app.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ErrorService {
    public ResponseEntity<Object> createApiError(HttpStatus status, String message, Throwable error) {
        String messageDescription = "";
        String errorNumber = StringUtils.createRandomErrorNumber();
        if(error != null)
            messageDescription = error.getMessage();

        if(message != null)
            message = "[Error number: " + errorNumber + "] " + message;

        if(error != null) {
            log.error(message + " " + messageDescription);
            error.printStackTrace();
        }else
            log.error(message);

        ApiError apiError = new ApiError(status, message, messageDescription);
        return new ResponseEntity<Object>(apiError, new HttpHeaders(), apiError.getStatus());
    }
}
