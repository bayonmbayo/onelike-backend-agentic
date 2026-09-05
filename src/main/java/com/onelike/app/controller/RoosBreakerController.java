package com.onelike.app.controller;

import com.onelike.app.service.ErrorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

@Slf4j
@CrossOrigin(exposedHeaders = "last")
@RequestMapping("/service/gaming/*")
@RestController
public class RoosBreakerController {

    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 16;

    @Value("${file.upload-dir}")
    private static String dmsDirectory;

    @Autowired
    ErrorService errorService;

    @GetMapping(value = "/roosbreaker/{username}")
    public String getRoosBreakerPlayer(@PathVariable String username) throws IOException {
        return "API created on June 30th 2022";
    }

    @PostMapping(value = "/roosbreaker/save", produces = "application/json")
    public String saveRoosBreakerPlayer(@RequestBody String gameData, HttpServletRequest request) throws Exception {


        return "saved";
    }

    public static String decryptFile(String encryptedData, String password, byte[] salt, byte[] iv) throws Exception {
        byte[] key = generateKey(password, salt);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData.getBytes(StandardCharsets.UTF_8));
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    public static byte[] generateKey(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, KEY_SIZE);
        SecretKey secret = factory.generateSecret(spec);
        return secret.getEncoded();
    }

    @GetMapping(value="/download/roosbreaker", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Object> getDocument() {
        try{
            // Document document = documentService.loadDocumentMetaData(dmsId, documentId);
            byte[] bytes  = loadBytes();

            if (bytes == null || bytes.length == 0) {
                System.out.println("Error");
                return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Das Dokument konnte nicht ausgelesen werden.", null);
            } else {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                        .header("Content-Disposition", "attachment; filename=" + "roosbreaker.apk")
                        .body(new ByteArrayResource(bytes));
            }
        }catch(Exception e){
            System.out.println("Error");
            return errorService.createApiError(HttpStatus.INTERNAL_SERVER_ERROR, "Das Dokument konnte nicht geladen werden.", e);
        }
    }

    public static byte[] loadBytes() throws Exception {
        return Files.readAllBytes(Path.of("C:\\Users\\Bayon\\test\\onelike", "roosbreaker.apk"));
    }

}
