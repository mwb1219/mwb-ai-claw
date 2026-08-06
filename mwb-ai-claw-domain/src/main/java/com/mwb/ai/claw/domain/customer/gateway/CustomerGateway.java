package com.mwb.ai.claw.domain.customer.gateway;

import com.mwb.ai.claw.domain.customer.Customer;

public interface CustomerGateway {
    Customer getByById(String customerId);
}
