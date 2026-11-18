package xsgao.k8s.order.ordering;

import jakarta.validation.constraints.NotBlank;

public record PlaceOrderRequest(@NotBlank String userId) {
}
