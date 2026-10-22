package com.darshan.ledger.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a controller method whose every attempt, successful or not, is written to audit_log.
// targetId and details are SpEL expressions over the method arguments; on success #result is the
// response body as well.
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    String action();

    String targetType();

    String targetId() default "";

    String details() default "";
}
