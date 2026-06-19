package com.fitness.admin.common.exception;

import com.fitness.admin.common.enums.ResultCodeEnum;
import com.fitness.admin.common.result.R;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验异常: {}", message);
        return R.error(ResultCodeEnum.PARAM_ERROR.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("约束违反: {}", message);
        return R.error(ResultCodeEnum.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数: " + e.getParameterName();
        log.warn("缺少参数: {}", e.getParameterName());
        return R.error(ResultCodeEnum.PARAM_MISSING.getCode(), message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数 " + e.getName() + " 类型不合法,期望 "
                + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "合法值")
                + ",实际: " + e.getValue();
        log.warn("参数类型不匹配: {}", message);
        return R.error(ResultCodeEnum.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        // 兼容中文/UTF-8 编码场景:提取根因给出可读 message
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        String message = "请求体格式错误: " + (detail != null ? detail : "无法解析 JSON");
        log.warn("请求体不可读: {}", message);
        return R.error(ResultCodeEnum.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String message = "请求方法不允许,支持的: " + (e.getSupportedHttpMethods() != null
                ? e.getSupportedHttpMethods()
                : "见接口文档");
        log.warn("方法不允许: {}", e.getMessage());
        return R.error(ResultCodeEnum.METHOD_NOT_ALLOWED.getCode(), message);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public R<Void> handleNotFound(Exception e) {
        log.warn("资源不存在: {}", e.getMessage());
        return R.error(ResultCodeEnum.NOT_FOUND.getCode(), "请求的资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.error(ResultCodeEnum.SYSTEM_ERROR);
    }
}
