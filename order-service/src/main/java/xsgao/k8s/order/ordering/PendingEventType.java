package xsgao.k8s.order.ordering;

/**
 * The kinds of event that can arrive out of order and must wait for the order to
 * reach a state where they become applicable.
 */
public enum PendingEventType {
    PRINT_COMPLETED
}
