package com.onelike.app.error;

import lombok.Data;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.List;

@Data
public class ApiError {

    private HttpStatus status;
    private String message;
    private List<String> errors;
    private List<String> warnings;

    public ApiError(HttpStatus status, String message, List<String> errors, List<String> warnings) {
        super();
        this.status = status;
        this.message = message;
        this.errors = errors;
        this.warnings = warnings;
    }

    public ApiError(HttpStatus status, String message, String error) {
        super();
        this.status = status;
        this.message = message;
        errors = Arrays.asList(error);
    }
}
