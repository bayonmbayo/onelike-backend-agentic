package com.onelike.app.exception;

public class InsufficientPrivilegesException extends Exception{
    public InsufficientPrivilegesException(String errorMessage) {
        super(errorMessage);
    }
}
