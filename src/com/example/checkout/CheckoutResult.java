package com.example.checkout;

public record CheckoutResult(String orderId, String fulfillmentStatus, String receiptId, String customerMessage) {}
