package com.example.checkout;

public record Order(String orderId, String customerEmail, String courseName, double amountUsd) {}
