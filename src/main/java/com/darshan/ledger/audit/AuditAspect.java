package com.darshan.ledger.audit;

import com.darshan.ledger.common.error.ErrorCodes;
import com.darshan.ledger.common.error.LedgerException;
import com.darshan.ledger.common.web.CorrelationIdFilter;
import com.darshan.ledger.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Runs outside the method-security interceptors on purpose: a request that is refused by
// @PreAuthorize is an attempt somebody made, and the trail must show it. Those interceptors are
// ordered from 100 upwards (AuthorizationInterceptorsOrder); 0 sits in front of them while still
// leaving Spring's own ExposeInvocationInterceptor at the very front, which argument binding needs.
@Aspect
@Component
@Order(AuditAspect.ORDER)
public class AuditAspect {

    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final String RESULT_VARIABLE = "result";

    private final AuditLogWriter writer;
    private final CurrentUser currentUser;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditLogWriter writer, CurrentUser currentUser) {
        this.writer = writer;
        this.currentUser = currentUser;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            record(joinPoint, audited, null, failure);
            throw failure;
        }
        record(joinPoint, audited, result, null);
        return result;
    }

    // Auditing must never turn a completed operation into an error for the caller; a failure to
    // write the row is logged loudly instead.
    private void record(ProceedingJoinPoint joinPoint, Audited audited, Object result, Throwable failure) {
        try {
            writer.write(eventFor(joinPoint, audited, result, failure));
        } catch (RuntimeException ex) {
            log.error("Audit row for action {} could not be written", audited.action(), ex);
        }
    }

    private AuditEvent eventFor(ProceedingJoinPoint joinPoint, Audited audited, Object result, Throwable failure) {
        EvaluationContext context = evaluationContext(joinPoint, unwrap(result));
        Map<String, Object> details = new LinkedHashMap<>(evaluateDetails(audited, context));
        if (failure instanceof LedgerException ex) {
            details.putAll(ex.extensions());
        }
        return new AuditEvent(
                currentUser.name(),
                currentUser.roles(),
                audited.action(),
                audited.targetType(),
                evaluateTargetId(audited, context),
                failure == null ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                failure == null ? null : ErrorCodes.of(failure).code(),
                clientIp(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                details);
    }

    private EvaluationContext evaluationContext(ProceedingJoinPoint joinPoint, Object result) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method,
                joinPoint.getArgs(), parameterNames);
        context.setVariable(RESULT_VARIABLE, result);
        return context;
    }

    private String evaluateTargetId(Audited audited, EvaluationContext context) {
        if (!StringUtils.hasText(audited.targetId())) {
            return null;
        }
        Object value = parse(audited.targetId()).getValue(context);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evaluateDetails(Audited audited, EvaluationContext context) {
        if (!StringUtils.hasText(audited.details())) {
            return Map.of();
        }
        Map<String, Object> details = parse(audited.details()).getValue(context, Map.class);
        return details == null ? Map.of() : details;
    }

    private Expression parse(String expression) {
        return parser.parseExpression(expression);
    }

    // Controllers return ResponseEntity for created resources; the audit expressions want the body.
    private static Object unwrap(Object result) {
        return result instanceof HttpEntity<?> entity ? entity.getBody() : result;
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }
}
