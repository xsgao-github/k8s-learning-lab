package xsgao.k8s.order.catalog;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class ProductDataInitializer {

    @Bean
    CommandLineRunner seedProducts(ProductRepository products) {
        return args -> {
            if (products.count() == 0) {
                products.saveAll(List.of(
                        new Product("Espresso", new BigDecimal("2.50")),
                        new Product("Cappuccino", new BigDecimal("3.75")),
                        new Product("Croissant", new BigDecimal("3.25")),
                        new Product("Bagel", new BigDecimal("2.95")),
                        new Product("Orange Juice", new BigDecimal("4.50"))
                ));
            }
        };
    }
}
