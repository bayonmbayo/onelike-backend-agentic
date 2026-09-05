package com.onelike.app.controller;


import com.onelike.app.exception.InsufficientPrivilegesException;
import com.onelike.app.model.GDPRData;
import com.onelike.app.service.ErrorService;
import com.onelike.app.service.GDPRDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/*")
@RestController
public class GDPRDataRequest {

    @Autowired
    ErrorService errorService;

    @Autowired
    GDPRDataService gdprDataService;

    @GetMapping(value="/gdprdatarequest", produces = "application/json")
    public ResponseEntity<Object> getGDPRRequest() {
        GDPRData gdprData = new GDPRData();
        return new ResponseEntity<>(gdprData, new HttpHeaders(), HttpStatus.OK);
    }

    @PostMapping(value="/gdprdatarequest", produces = "application/json")
    public ResponseEntity<Object> createGDPRDataRequest(@RequestBody GDPRData gdprData) {
        try{
            return new ResponseEntity<>(gdprDataService.save(gdprData), new HttpHeaders(), HttpStatus.CREATED);
        } catch(Exception e){
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "The GDPR Request can't be processed", e);
        }
    }
}
