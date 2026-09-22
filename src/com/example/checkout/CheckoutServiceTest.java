package com.example.checkout;

public class CheckoutServiceTest {
    public static void main(String[] args) throws Exception {
        CheckoutService service = new CheckoutService(new InfraiClient("test-key") {
            @Override public String configureAutorecharge(double trigger, double amount) { return "{\"ok\":true}"; }
            @Override public String balance() { return "{\"ok\":true,\"data\":{\"balance\":1.0}}"; }
            @Override public String sendOrderUpdate(String to, String subject, String text) { return "{\"ok\":true,\"data\":{\"message_id\":\"m-1\"}}"; }
        });
        CheckoutResult result = service.checkout(new Order("t-1", "learner@example.edu", "Geometry", 20.0));
        if (!"FULFILLED".equals(result.fulfillmentStatus())) throw new AssertionError(result);
        if (!"receipt-t-1".equals(result.receiptId())) throw new AssertionError(result);
        System.out.println("CheckoutServiceTest passed: low balance keeps the order moving");
    }
}
