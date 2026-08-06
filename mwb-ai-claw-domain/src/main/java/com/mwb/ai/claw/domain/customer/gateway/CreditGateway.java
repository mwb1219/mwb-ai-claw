package com.mwb.ai.claw.domain.customer.gateway;

import com.mwb.ai.claw.domain.customer.Credit;

//Assume that the credit info is in another distributed Service
public interface CreditGateway {
    Credit getCredit(String customerId);
}
