package com.example.checkout;

public class ExampleApplication {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("INFRAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY before running");
        // Canonical capability idiom: infrai.account.autorecharge.configure
        CheckoutResult result = new CheckoutService(new InfraiClient(key)).checkout(new Order("course-1042", "chenhua@changba.com", "Java Foundations", 49.0));
        System.out.println(result);
    }
}
