package com.ecclesiaflow.platform.upload.logging;

import com.ecclesiaflow.platform.upload.MagicBytes;
import com.ecclesiaflow.platform.upload.SanitizedUpload;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

/**
 * Traces accepted uploads at DEBUG: detected types and sizes, never content.
 * Rejections propagate as {@code UploadRejectedException} and are logged by the
 * calling module's boundary.
 */
@Slf4j
@Aspect
public class UploadSanitizerLoggingAspect {

    @Pointcut("execution(com.ecclesiaflow.platform.upload.SanitizedUpload "
            + "com.ecclesiaflow.platform.upload.ImageSanitizer.sanitize(byte[], ..)) && args(input, ..)")
    public void imageSanitized(byte[] input) {}

    @Pointcut("execution(com.ecclesiaflow.platform.upload.SanitizedUpload "
            + "com.ecclesiaflow.platform.upload.FileSanitizer.sanitizeFile(..))")
    public void fileSanitized() {}

    @AfterReturning(pointcut = "imageSanitized(input)", returning = "result", argNames = "input,result")
    public void logImageSanitized(byte[] input, SanitizedUpload result) {
        if (log.isDebugEnabled()) {
            log.debug("UPLOAD-SANITIZE: {} ({} bytes) -> {} ({} bytes)",
                    MagicBytes.detect(input).orElse("unknown"), input.length,
                    result.contentType(), result.size());
        }
    }

    @AfterReturning(pointcut = "fileSanitized()", returning = "result")
    public void logFileAccepted(SanitizedUpload result) {
        log.debug("UPLOAD-SANITIZE(file): accepted {} ({} bytes)", result.contentType(), result.size());
    }
}
