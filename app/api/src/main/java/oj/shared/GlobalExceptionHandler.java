package oj.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * 统一异常处理：对外仅返回错误码、用户可读消息与追踪 ID；
 * 堆栈、数据库错误、内部 IP 与文件路径只写内部日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(String code, String message, String traceId) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApiException(ApiException e) {
        String traceId = newTraceId();
        log.info("业务异常 code={} traceId={} msg={}", e.errorCode().code(), traceId, e.getMessage());
        return ResponseEntity.status(e.errorCode().httpStatus())
                .body(new ErrorBody(e.errorCode().code(), e.getMessage(), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String traceId = newTraceId();
        FieldError fieldError = e.getBindingResult().getFieldError();
        String detail = fieldError == null ? "参数不合法" : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody(ErrorCode.VALIDATION_FAILED.code(), detail, traceId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> handleIllegalState(IllegalStateException e) {
        String traceId = newTraceId();
        log.error("内部状态异常 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(ErrorCode.INTERNAL_ERROR.code(),
                        ErrorCode.INTERNAL_ERROR.defaultMessage(), traceId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleGeneric(Exception e) {
        String traceId = newTraceId();
        log.error("未处理异常 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(ErrorCode.INTERNAL_ERROR.code(),
                        ErrorCode.INTERNAL_ERROR.defaultMessage(), traceId));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
