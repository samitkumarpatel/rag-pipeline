package dev.samitkumar.ragpipeline.ingestion.internal;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

@RestControllerAdvice
class IngestionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionExceptionHandler.class);

    private static final String TYPE_BASE = "https://rag-platform.example.com/problems/";

    @ExceptionHandler(ArchiveExtractor.UnsupportedArchiveException.class)
    ProblemDetail handleUnsupportedArchive(ArchiveExtractor.UnsupportedArchiveException ex,
                                            WebRequest request) {
        log.warn("Unsupported archive: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setTitle("Unsupported Archive Format");
        pd.setType(URI.create(TYPE_BASE + "unsupported-archive"));
        return pd;
    }

    @ExceptionHandler(FileTypeValidator.UnsupportedFileTypeException.class)
    ProblemDetail handleUnsupportedFileType(FileTypeValidator.UnsupportedFileTypeException ex,
                                             WebRequest request) {
        log.warn("Unsupported file type: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setTitle("Unsupported File Type");
        pd.setType(URI.create(TYPE_BASE + "unsupported-file-type"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Invalid Request");
        pd.setType(URI.create(TYPE_BASE + "invalid-request"));
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ex.getMessage());
        log.warn("Validation failed: {}", detail);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation Failed");
        pd.setType(URI.create(TYPE_BASE + "validation-error"));
        return pd;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleMaxUploadSize(MaxUploadSizeExceededException ex, WebRequest request) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Upload exceeds the maximum allowed size. Please compress your archive further.");
        pd.setTitle("Upload Too Large");
        pd.setType(URI.create(TYPE_BASE + "upload-too-large"));
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResourceFound(NoResourceFoundException ex, WebRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Resource Not Found");
        pd.setType(URI.create(TYPE_BASE + "resource-not-found"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneral(Exception ex, WebRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        pd.setTitle("Internal Server Error");
        pd.setType(URI.create(TYPE_BASE + "internal-error"));
        return pd;
    }
}
