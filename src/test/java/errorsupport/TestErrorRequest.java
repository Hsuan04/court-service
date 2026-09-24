package errorsupport;

import jakarta.validation.constraints.NotBlank;

record TestErrorRequest(@NotBlank(message = "must not be blank") String name) {
}