package com.example.checkout;

import java.io.IOException;

public class CheckoutService {
    private final InfraiClient infrai;

    public CheckoutService(InfraiClient infrai) { this.infrai = infrai; }

    public CheckoutResult checkout(Order order) throws IOException, InterruptedException {
        infrai.configureAutorecharge(5.0, 25.0);
        String balanceEnvelope = infrai.balance();
        String fulfillment = balanceEnvelope.contains("\"ok\":true") ? "FULFILLED" : "PENDING";
        String receipt = "receipt-" + order.orderId();
        String message = "Your " + order.courseName() + " order is " + fulfillment.toLowerCase() + ". Receipt " + receipt + ".";
        infrai.sendOrderUpdate(order.customerEmail(), "Order " + order.orderId() + " update", message);
        return new CheckoutResult(order.orderId(), fulfillment, receipt, message);
    }
}
