package com.challenge.balance.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Account identifiers must be safe as a single URL path segment. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Size(max = 255)
@Pattern(regexp = "(?!\\.{1,2}$)[A-Za-z0-9._~-]+",
        message = "must contain only letters, digits, '.', '_', '~', or '-' and must not be '.' or '..'")
public @interface ValidAccountId {
    String message() default "Invalid account ID";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
