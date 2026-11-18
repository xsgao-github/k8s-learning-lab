package xsgao.k8s.order.catalog;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    public List<ProductResponse> listProducts() {
        return products.findAll(Sort.by("id")).stream()
                .map(ProductResponse::from)
                .toList();
    }
}
